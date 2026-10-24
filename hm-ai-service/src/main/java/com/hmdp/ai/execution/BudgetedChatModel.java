package com.hmdp.ai.execution;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.Set;

/** 在真实模型边界统一检查预算；多个工具共享的后续回合只预留一次。 */
@Slf4j
public final class BudgetedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final AiExecutionMetrics metrics;
    private final Duration timeout;
    private final int maxOutputTokens;

    public BudgetedChatModel(ChatModel delegate, AiExecutionMetrics metrics,
                             Duration timeout, int maxOutputTokens) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.timeout = timeout;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }

    @Override
    public ModelProvider provider() { return delegate.provider(); }

    @Override
    public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }

    /** 新版所有同步 chat 重载最终进入此处，不能绕过模型回合、Token 和截止时间检查。 */
    @Override
    public ChatResponse doChat(ChatRequest request) {
        AiExecutionBudget budget = AiExecutionBudget.current();
        // Tokenizer 只作准入估计；非 OpenAI 模型的计费以供应商 usage 为准。
        int inputEstimate = delegate instanceof TokenCountEstimator estimator
                ? estimator.estimateTokenCountInMessages(request.messages()) : estimate(request.messages().toString());
        int reserve = inputEstimate + estimate(request.toolSpecifications() == null ? "" : request.toolSpecifications().toString()) + maxOutputTokens;
        if (budget != null) budget.reserveModelCall(reserve, timeout);
        String scene = budget == null ? "unscoped" : budget.getScene();
        long started = System.nanoTime();
        TokenUsage usage = null;
        String outcome = "error";
        try {
            ChatResponse response = delegate.chat(request);
            usage = response == null ? null : response.tokenUsage();
            if (budget != null) {
                budget.settleModelTokens(reserve, usage == null ? null : usage.totalTokenCount());
                budget.requireTime(Duration.ZERO);
            }
            outcome = "success";
            return response;
        } finally {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            metrics.modelCall(scene, outcome, Duration.ofMillis(elapsed), usage);
            log.info("AI_MODEL_CALL requestId={}, scene={}, round={}, durationMs={}, outcome={}, reserved={}, inputTokens={}, outputTokens={}",
                    budget == null ? "none" : budget.getRequestId(), scene,
                    budget == null ? 0 : budget.getModelCalls(), elapsed, outcome, reserve,
                    usage == null ? null : usage.inputTokenCount(), usage == null ? null : usage.outputTokenCount());
        }
    }

    private int estimate(String text) { return (text.codePointCount(0, text.length()) + 1) / 2; }
}
