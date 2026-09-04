package com.hmdp.config;

import com.hmdp.service.ai.AiCustomerTools;
import com.hmdp.service.ai.AiToolCallBudget;
import com.hmdp.service.ai.AgentExecutionLogService;
import com.hmdp.service.ai.CustomerAgent;
import com.hmdp.service.ai.ToolRetryHandler;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.cohere.CohereScoringModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集中创建 LangChain4j 模型和客服 Agent；未启用模型时不创建外部模型 Bean，保证基础服务可独立启动。
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class LangChain4jConfig {

    @Value("${langchain4j.openai.api-key:}")
    private String apiKey;

    @Value("${langchain4j.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${langchain4j.openai.embedding-api-key:}")
    private String embeddingApiKey;

    @Value("${langchain4j.openai.embedding-base-url:https://api.openai.com/v1}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.openai.model-name:gpt-4}")
    private String modelName;

    @Value("${langchain4j.openai.embedding-model-name:text-embedding-3-small}")
    private String embeddingModelName;

    @Value("${milvus.embedding-dimension:1536}")
    private int embeddingDimension;

    @Value("${langchain4j.openai.temperature:0.7}")
    private double temperature;

    @Value("${langchain4j.openai.max-tokens:512}")
    private int maxTokens;

    @Value("${langchain4j.openai.timeout:15s}")
    private Duration timeout;

    @Value("${langchain4j.openai.max-retries:2}")
    private int maxRetries;

    @Value("${langchain4j.cohere.api-key:}")
    private String cohereApiKey;

    @Value("${langchain4j.cohere.base-url:https://api.cohere.ai}")
    private String cohereBaseUrl;

    @Value("${langchain4j.cohere.model-name:rerank-multilingual-v3.0}")
    private String cohereModelName;

    @Value("${langchain4j.cohere.timeout:2s}")
    private Duration cohereTimeout;

    @Value("${langchain4j.cohere.max-retries:1}")
    private int cohereMaxRetries;

    /** 创建 OpenAI 兼容的聊天模型，超时和重试直接落在 LangChain4j 客户端上。 */
    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.openai", name = "enabled", havingValue = "true")
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
    }

    /** 仅在开启向量召回时创建 Embedding，避免普通客服启动时额外调用外部模型。 */
    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.openai", name = "embedding-enabled", havingValue = "true")
    public EmbeddingModel embeddingModel() {
        if (embeddingDimension <= 0) {
            throw new IllegalStateException("milvus.embedding-dimension 必须大于 0");
        }
        log.info("初始化 EmbeddingModel，model={}, dimension={}", embeddingModelName, embeddingDimension);
        return OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName)
                .dimensions(embeddingDimension)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .build();
    }

    /** 仅显式开启且配置密钥时接入 Cohere，默认不产生额外外部调用。 */
    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.cohere", name = "enabled", havingValue = "true")
    public ScoringModel scoringModel() {
        if (cohereApiKey == null || cohereApiKey.isBlank()) {
            throw new IllegalStateException("启用 Cohere Rerank 时必须配置 COHERE_API_KEY");
        }
        return CohereScoringModel.builder()
                .apiKey(cohereApiKey)
                .baseUrl(cohereBaseUrl)
                .modelName(cohereModelName)
                .timeout(cohereTimeout)
                .maxRetries(Math.max(0, cohereMaxRetries))
                .build();
    }

    /**
     * 使用 LangChain4j 的工具编排，同时用包装器保留原有的请求级工具调用预算。
     * 会话历史仍由 Redis 会话服务拼入提示词，避免共享 ChatMemory 造成用户间串话。
     */
    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.openai", name = "enabled", havingValue = "true")
    public CustomerAgent customerAgent(ChatLanguageModel chatLanguageModel,
                                       AiCustomerTools tools,
                                       AiToolCallBudget toolCallBudget,
                                       AgentExecutionLogService executionLogService,
                                       ToolRetryHandler retryHandler) {
        Map<ToolSpecification, ToolExecutor> toolExecutors = new LinkedHashMap<>();
        for (Method method : AiCustomerTools.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            DefaultToolExecutor delegate = new DefaultToolExecutor(tools, method);
            ToolExecutor retryingDelegate = (request, memoryId) -> retryHandler.executeWithRetry(
                    () -> delegate.execute(request, memoryId), request.name());
            ToolExecutor budgetedDelegate = (request, memoryId) ->
                    toolCallBudget.executeTool(request, memoryId, retryingDelegate);
            // 预算只按一次模型工具调用扣减，网络抖动导致的重试仍归属于同一工具步骤。
            toolExecutors.put(specification, (request, memoryId) -> executionLogService.executeToolCall(
                    request.name(), request.arguments(),
                    () -> budgetedDelegate.execute(request, memoryId)));
        }
        return AiServices.builder(CustomerAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(toolExecutors)
                .build();
    }
}
