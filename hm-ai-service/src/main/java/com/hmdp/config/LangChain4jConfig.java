package com.hmdp.config;

import com.hmdp.service.ai.AiCustomerTools;
import com.hmdp.service.ai.AiToolCallBudget;
import com.hmdp.service.ai.CustomerAgent;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
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
public class LangChain4jConfig {

    @Value("${langchain4j.openai.api-key:}")
    private String apiKey;

    @Value("${langchain4j.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${langchain4j.openai.model-name:gpt-4}")
    private String modelName;

    @Value("${langchain4j.openai.embedding-model-name:text-embedding-3-small}")
    private String embeddingModelName;

    @Value("${langchain4j.openai.temperature:0.7}")
    private double temperature;

    @Value("${langchain4j.openai.max-tokens:512}")
    private int maxTokens;

    @Value("${langchain4j.openai.timeout:15s}")
    private Duration timeout;

    @Value("${langchain4j.openai.max-retries:2}")
    private int maxRetries;

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
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(embeddingModelName)
                .timeout(timeout)
                .maxRetries(maxRetries)
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
                                       AiToolCallBudget toolCallBudget) {
        Map<ToolSpecification, ToolExecutor> toolExecutors = new LinkedHashMap<>();
        for (Method method : AiCustomerTools.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            DefaultToolExecutor delegate = new DefaultToolExecutor(tools, method);
            toolExecutors.put(specification,
                    (request, memoryId) -> toolCallBudget.executeTool(request, memoryId, delegate));
        }
        return AiServices.builder(CustomerAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(toolExecutors)
                .build();
    }
}
