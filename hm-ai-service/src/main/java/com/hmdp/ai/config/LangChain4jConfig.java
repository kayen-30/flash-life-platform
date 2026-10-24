package com.hmdp.ai.config;

import com.hmdp.ai.agent.CustomerAgent;
import com.hmdp.ai.customer.AiBusinessFacts;
import com.hmdp.ai.skill.AiBusinessSkills;
import com.hmdp.ai.execution.AiExecutionBudget;
import com.hmdp.ai.execution.AiExecutionMetrics;
import com.hmdp.ai.execution.BudgetedChatModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import java.util.List;
import com.hmdp.ai.agent.log.AgentExecutionLogService;
import com.hmdp.ai.agent.tool.AiCustomerTools;
import com.hmdp.ai.agent.tool.AiToolCallBudget;
import com.hmdp.ai.agent.tool.ToolRetryHandler;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.ChatModel;
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

    @Value("${langchain4j.openai.temperature:0.3}")
    private double temperature;

    @Value("${langchain4j.openai.max-tokens:512}")
    private int maxTokens;

    @Value("${langchain4j.openai.timeout:10s}")
    private Duration timeout;

    @Value("${langchain4j.openai.max-retries:0}")
    private int maxRetries;

    @Value("${langchain4j.openai.enable-thinking:#{null}}")
    private Boolean enableThinking;

    @Value("${langchain4j.openai.single-message-compat:false}")
    private boolean singleMessageCompat;

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
    public ChatModel chatLanguageModel(AiExecutionMetrics metrics) {
        if (maxRetries != 0) {
            throw new IllegalStateException("请求截止时间模式要求 langchain4j.openai.max-retries=0");
        }
        var builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(timeout)
                // 请求总时限不能被 SDK 内部重试放大。
                .maxRetries(0);
        // 仅显式配置时发送扩展字段；短客服不应让思考耗尽512预算而没有最终答案。
        if (enableThinking != null) {
            builder.customParameters(Map.of("thinking", Map.of("type", enableThinking ? "enabled" : "disabled")));
        }
        ChatModel delegate = builder.build();
        return new BudgetedChatModel(delegate, metrics, timeout, maxTokens);
    }

    /** 仅在开启向量召回时创建 Embedding，避免普通客服启动时额外调用外部模型。 */
    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.openai", name = "embedding-enabled", havingValue = "true")
    public EmbeddingModel embeddingModel(AiExecutionMetrics metrics) {
        if (embeddingDimension <= 0) {
            throw new IllegalStateException("milvus.embedding-dimension 必须大于 0");
        }
        log.info("初始化 EmbeddingModel，model={}, dimension={}", embeddingModelName, embeddingDimension);
        EmbeddingModel delegate = OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName)
                .dimensions(embeddingDimension)
                .timeout(Duration.ofSeconds(4))
                .maxRetries(0)
                .build();
        return new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                AiExecutionBudget budget = AiExecutionBudget.current();
                if (budget != null) budget.requireTime(Duration.ofSeconds(4));
                long started = System.nanoTime();
                Response<List<Embedding>> response = null;
                String outcome = "error";
                try {
                    response = delegate.embedAll(segments);
                    if (budget != null) budget.requireTime(Duration.ZERO);
                    outcome = "success";
                    return response;
                } finally {
                    metrics.modelCall("embedding", outcome, Duration.ofNanos(System.nanoTime() - started),
                            response == null ? null : response.tokenUsage());
                }
            }
        };
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
     * 模型只接收已校验的任务与本轮数据，不共享用户间的 ChatMemory。
     */
    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.openai", name = "enabled", havingValue = "true")
    public CustomerAgent customerAgent(ChatModel chatLanguageModel,
                                       AiCustomerTools tools,
                                       AiToolCallBudget toolCallBudget,
                                       AgentExecutionLogService executionLogService,
                                       ToolRetryHandler retryHandler,
                                       AiBusinessSkills businessSkills) {
        // 单消息兼容不模拟工具调用角色；不能把native协议静默展平后继续执行。
        if (singleMessageCompat && businessSkills.requiresActivation("inspect_voucher_conditions")) {
            throw new IllegalStateException("单消息兼容模式不支持native Skill，请配置inline或关闭Skill");
        }
        Map<ToolSpecification, ToolExecutor> toolExecutors = new LinkedHashMap<>();
        for (Method method : AiCustomerTools.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
            // 组合工具内部也可能触发预算异常，不能被SDK转成“成功”的普通结果文本。
            DefaultToolExecutor delegate = DefaultToolExecutor.builder().object(tools)
                    .originalMethod(method).methodToInvoke(method).propagateToolExecutionExceptions(true).build();
            ToolExecutor retryingDelegate = (request, memoryId) -> retryHandler.executeWithRetry(
                    () -> delegate.execute(request, memoryId), request.name());
            ToolExecutor budgetedDelegate = (request, memoryId) ->
                    // 条件读取只访问本轮内存快照；仍扣一次工具预算，不预留Feign网络时间。
                    toolCallBudget.executeTool(request, "inspect_voucher_conditions".equals(request.name())
                            ? java.time.Duration.ZERO : java.time.Duration.ofSeconds(4),
                            () -> retryingDelegate.execute(request, memoryId));
            // 预算只按一次模型工具调用扣减，网络抖动导致的重试仍归属于同一工具步骤。
            toolExecutors.put(specification, (request, memoryId) -> executionLogService.executeToolCall(
                    request.name(), request.arguments(),
                    () -> AiBusinessFacts.record(request.name(), request.arguments(),
                            budgetedDelegate.execute(request, memoryId))));
        }
        return AiServices.builder(CustomerAgent.class)
                .chatModel(chatLanguageModel)
                // 已处理的业务失败由工具返回；其余异常（含预算）必须终止本轮，不能再交给模型续跑。
                .toolExecutionErrorHandler(dev.langchain4j.service.tool.ToolExecutionErrorHandler.failInvocation())
                // 官方目录按需激活；动态工具提供器在每个模型回合重新计算授权目录。
                .systemMessageTransformer(system -> system + businessSkills.catalog())
                .toolProvider(businessSkills.toolProvider(toolExecutors, toolCallBudget, executionLogService))
                .build();
    }
}
