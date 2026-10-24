package com.hmdp.ai.execution;

import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import com.hmdp.ai.agent.tool.AiToolCallBudgetExceededException;

/**
 * 单次 AI 请求的本地预算，调用方在发起工具或模型调用前预扣额度。
 */
public final class AiExecutionBudget {

    private static final ThreadLocal<AiExecutionBudget> CURRENT = new ThreadLocal<>();
    private final String requestId = UUID.randomUUID().toString();
    private final long deadlineNanos;
    private final AtomicInteger remainingModelCalls;
    private final int maxModelCalls;
    private final String scene;
    private final AtomicInteger remainingToolCalls;
    private final AtomicInteger remainingTokens;
    private final AiExecutionMetrics metrics;

    AiExecutionBudget(String scene, int maxToolCalls, int tokenBudget, AiExecutionMetrics metrics, int maxModelCalls, Duration timeout) {
        this.scene = scene;
        this.maxModelCalls = maxModelCalls;
        this.remainingModelCalls = new AtomicInteger(maxModelCalls);
        this.deadlineNanos = System.nanoTime() + timeout.toNanos();
        this.remainingToolCalls = new AtomicInteger(maxToolCalls);
        this.remainingTokens = new AtomicInteger(tokenBudget);
        this.metrics = metrics;
    }

    public Duration remainingTime() { return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())); }

    public static AiExecutionBudget current() { return CURRENT.get(); }
    public String getRequestId() { return requestId; }
    public String getScene() { return scene; }
    public int getModelCalls() { return maxModelCalls - remainingModelCalls.get(); }
    public int getRemainingModelCalls() { return remainingModelCalls.get(); }

    /** 同步链路共用预算，finally 恢复上下文；异步记忆不继承用户请求。 */
    public <T> T call(Supplier<T> action) {
        AiExecutionBudget previous = CURRENT.get();
        CURRENT.set(this);
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    /** 为网络调用及返回留余量，不用线程池假取消仍在运行的请求。 */
    public void requireTime(Duration callTimeout) {
        if (Thread.currentThread().isInterrupted()
                || deadlineNanos - System.nanoTime() <= callTimeout.toNanos() + Duration.ofMillis(300).toNanos()) {
            metrics.budgetExhausted(scene, "deadline");
            throw new AiDeadlineExceededException();
        }
    }

    /** 模型回合与 Token 一起准入；未获准的请求不能被统计成已调用模型。 */
    public synchronized void reserveModelCall(int estimatedTokens, Duration timeout) {
        requireTime(timeout);
        if (remainingModelCalls.get() == 0) {
            metrics.budgetExhausted(scene, "model_calls");
            throw new AiToolCallBudgetExceededException();
        }
        if (!tryConsumeTokens(estimatedTokens)) throw new AiToolCallBudgetExceededException();
        // 只有本方法扣减模型次数；同步准入保证两个余额不会只扣成功一半。
        remainingModelCalls.decrementAndGet();
        metrics.budgetConsumed(scene, "model_calls", 1);
    }

    /** usage 缺失时保留预留值；已知用量可校正，负余额不允许再开回合。 */
    public void settleModelTokens(int reserved, Integer actual) {
        if (actual != null && actual >= 0) remainingTokens.addAndGet(reserved - actual);
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
