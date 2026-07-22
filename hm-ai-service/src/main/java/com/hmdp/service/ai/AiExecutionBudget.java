package com.hmdp.service.ai;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次 AI 请求的本地预算，调用方在发起工具或模型调用前预扣额度。
 */
public final class AiExecutionBudget {

    private final String scene;
    private final AtomicInteger remainingToolCalls;
    private final AtomicInteger remainingTokens;
    private final AiExecutionMetrics metrics;

    AiExecutionBudget(String scene, int maxToolCalls, int tokenBudget, AiExecutionMetrics metrics) {
        this.scene = scene;
        this.remainingToolCalls = new AtomicInteger(maxToolCalls);
        this.remainingTokens = new AtomicInteger(tokenBudget);
        this.metrics = metrics;
    }

    public boolean tryConsumeToolCall() {
        return tryConsumeToolCalls(1);
    }

    public boolean tryConsumeToolCalls(int count) {
        return tryConsume(remainingToolCalls, count, "tool_calls");
    }

    public boolean tryConsumeTokens(int tokens) {
        return tryConsume(remainingTokens, tokens, "tokens");
    }

    public int getRemainingToolCalls() {
        return remainingToolCalls.get();
    }

    public int getRemainingTokens() {
        return remainingTokens.get();
    }

    private boolean tryConsume(AtomicInteger remaining, int amount, String budgetType) {
        if (amount <= 0) {
            throw new IllegalArgumentException("预算扣减值必须大于 0");
        }
        while (true) {
            int current = remaining.get();
            if (current < amount) {
                metrics.budgetExhausted(scene, budgetType);
                return false;
            }
            if (remaining.compareAndSet(current, current - amount)) {
                metrics.budgetConsumed(scene, budgetType, amount);
                return true;
            }
        }
    }
}
