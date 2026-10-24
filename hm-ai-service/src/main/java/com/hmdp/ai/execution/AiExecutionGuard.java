package com.hmdp.ai.execution;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Predicate;
import com.hmdp.ai.agent.tool.AiToolCallBudgetExceededException;

/**
 * 为模型调用提供本机并发隔离、按场景熔断和请求级预算入口。
 */
@Component
public class AiExecutionGuard {

    private final Semaphore concurrentRequests;
    private final long acquireTimeoutNanos;
    private final int circuitFailureThreshold;
    private final long circuitOpenDurationNanos;
    private final int maxToolCalls;
    private final int tokenBudgetPerRequest;
    private final int maxModelCalls;
    private final Duration requestTimeout;
    private final AiExecutionMetrics metrics;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

    public AiExecutionGuard(AiExecutionProperties properties, AiExecutionMetrics metrics) {
        this.concurrentRequests = new Semaphore(requirePositive(properties.getMaxConcurrentRequests(), "max-concurrent-requests"), true);
        this.acquireTimeoutNanos = requireNonNegative(properties.getAcquireTimeout(), "acquire-timeout").toNanos();
        this.circuitFailureThreshold = requirePositive(properties.getCircuitFailureThreshold(), "circuit-failure-threshold");
        this.circuitOpenDurationNanos = requirePositive(properties.getCircuitOpenDuration(), "circuit-open-duration").toNanos();
        this.maxToolCalls = requirePositive(properties.getMaxToolCalls(), "max-tool-calls");
        this.tokenBudgetPerRequest = requirePositive(properties.getTokenBudgetPerRequest(), "token-budget-per-request");
        this.maxModelCalls = requirePositive(properties.getMaxModelCalls(), "max-model-calls");
        this.requestTimeout = requirePositive(properties.getRequestTimeout(), "request-timeout");
        this.metrics = metrics;
    }

    /**
     * 包装同步模型调用，自动完成并发释放、熔断状态更新与耗时指标记录。
     */
    public <T> T execute(String scene, Supplier<T> action) {
        return execute(scene, action, result -> true);
    }

    /** 部分结果可继续交付；协议漏项仍记为失败，不能用HTTP成功关闭半开熔断。 */
    public <T> T execute(String scene, Supplier<T> action, Predicate<T> protocolComplete) {
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(protocolComplete, "protocolComplete 不能为空");
        AiExecutionBudget budget = AiExecutionBudget.current();
        if (budget != null) {
            Duration stageTimeout = switch (scene) {
                case "rag", "memory", "rerank" -> Duration.ofSeconds(2);
                default -> Duration.ZERO;
            };
            budget.requireTime(stageTimeout);
        }
        try (ExecutionPermit permit = tryAcquire(scene)) {
            if (!permit.isGranted()) {
                throw new AiExecutionRejectedException(permit.getRejectionReason());
            }
            try {
                T result = action.get();
                if (budget != null) budget.requireTime(Duration.ZERO);
                if (protocolComplete.test(result)) permit.success();
                else permit.failure(new AiModelProtocolException("模型响应协议不完整"));
                return result;
            } catch (RuntimeException | Error exception) {
                permit.failure(exception);
                throw exception;
            }
        }
    }

    /**
     * 流式调用等无法直接使用 execute 时，可通过 permit 显式标记成功或失败。
     */
    public ExecutionPermit tryAcquire(String scene) {
        String normalizedScene = requireScene(scene);
        CircuitState circuit = circuits.computeIfAbsent(normalizedScene, key -> new CircuitState());
        CircuitAdmission admission = circuit.tryAllow(System.nanoTime());
        if (admission == null) {
            metrics.rejected(normalizedScene, AiExecutionRejectionReason.CIRCUIT_OPEN);
            return rejectedPermit(normalizedScene, AiExecutionRejectionReason.CIRCUIT_OPEN);
        }

        boolean acquired;
        try {
            acquired = concurrentRequests.tryAcquire(acquireTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            circuit.releaseHalfOpenProbe(admission);
            metrics.rejected(normalizedScene, AiExecutionRejectionReason.CONCURRENCY_LIMIT);
            return rejectedPermit(normalizedScene, AiExecutionRejectionReason.CONCURRENCY_LIMIT);
        }

        metrics.executionStarted();
        return grantedPermit(normalizedScene, circuit, admission);
    }

    public AiExecutionBudget newBudget(String scene) {
        return new AiExecutionBudget(requireScene(scene), maxToolCalls, tokenBudgetPerRequest, metrics, maxModelCalls, requestTimeout);
    }

    private ExecutionPermit rejectedPermit(String scene, AiExecutionRejectionReason reason) {
        return new ExecutionPermit(scene, null, null, reason, 0L);
    }

    private ExecutionPermit grantedPermit(String scene, CircuitState circuit, CircuitAdmission admission) {
        return new ExecutionPermit(scene, circuit, admission, null, System.nanoTime());
    }

    private boolean shouldCountCircuitFailure(Throwable exception) {
        // 编排框架可能包装本地拒绝；总时限与预算不足均不代表供应商故障。
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof AiExecutionRejectedException || cause instanceof AiDeadlineExceededException
                    || cause instanceof AiToolCallBudgetExceededException) return false;
        }
        // 响应协议错误来自模型，不能混同于调用方参数错误而漏计熔断。
        return exception instanceof AiModelProtocolException || !(exception instanceof IllegalArgumentException);
    }

    private String requireScene(String scene) {
        if (scene == null || scene.isBlank()) {
            throw new IllegalArgumentException("AI 调用场景不能为空");
        }
        return scene;
    }

    private int requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalArgumentException("hmdp.ai.execution." + propertyName + " 必须大于 0");
        }
        return value;
    }

    private Duration requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("hmdp.ai.execution." + propertyName + " 必须大于 0");
        }
        return value;
    }

    private Duration requireNonNegative(Duration value, String propertyName) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException("hmdp.ai.execution." + propertyName + " 不能小于 0");
        }
        return value;
    }

    public final class ExecutionPermit implements AutoCloseable {

        private final String scene;
        private final CircuitState circuit;
        private final CircuitAdmission admission;
        private final AiExecutionRejectionReason rejectionReason;
        private final long startedAtNanos;
        private boolean completed;
        private boolean closed;

        private ExecutionPermit(String scene, CircuitState circuit, CircuitAdmission admission,
                                AiExecutionRejectionReason rejectionReason, long startedAtNanos) {
            this.scene = scene;
            this.circuit = circuit;
            this.admission = admission;
            this.rejectionReason = rejectionReason;
            this.startedAtNanos = startedAtNanos;
        }

        public boolean isGranted() {
            return rejectionReason == null;
        }

        public AiExecutionRejectionReason getRejectionReason() {
            return rejectionReason;
        }

        public void success() {
            complete("success", null);
        }

        public void failure(Throwable exception) {
            Objects.requireNonNull(exception, "exception 不能为空");
            complete("failure", exception);
        }

        private void complete(String outcome, Throwable exception) {
            boolean countCircuitFailure;
            boolean circuitOpen;
            synchronized (this) {
                if (!isGranted() || completed) {
                    return;
                }
                completed = true;
                countCircuitFailure = exception != null && shouldCountCircuitFailure(exception);
                if (exception == null) {
                    circuitOpen = circuit.recordSuccess(admission);
                } else if (countCircuitFailure) {
                    circuitOpen = circuit.recordFailure(admission, circuitFailureThreshold, circuitOpenDurationNanos);
                } else {
                    // 4xx 等非服务故障不能卡住半开探测，释放后允许下一次真实模型调用继续验证恢复情况。
                    circuitOpen = circuit.releaseHalfOpenProbe(admission);
                }
            }
            if (exception == null || countCircuitFailure || admission.halfOpenProbe()) {
                metrics.circuitState(scene, circuitOpen);
            }
            metrics.executionFinished(scene, outcome, elapsed());
        }

        @Override
        public void close() {
            boolean reportAbandoned = false;
            synchronized (this) {
                if (!isGranted() || closed) {
                    return;
                }
                closed = true;
                if (!completed) {
                    completed = true;
                    reportAbandoned = true;
                }
            }
            if (reportAbandoned) {
                // 异常控制流未显式标记结果时，半开探测位也必须归还，避免熔断器永久卡在恢复阶段。
                boolean circuitOpen = circuit.releaseHalfOpenProbe(admission);
                if (admission.halfOpenProbe()) {
                    metrics.circuitState(scene, circuitOpen);
                }
                metrics.executionFinished(scene, "abandoned", elapsed());
            }
            concurrentRequests.release();
        }

        private Duration elapsed() {
            return Duration.ofNanos(System.nanoTime() - startedAtNanos);
        }
    }

    private record CircuitAdmission(long generation, boolean halfOpenProbe) { }

    private static final class CircuitState {

        private int consecutiveFailures;
        private long openUntilNanos;
        private boolean halfOpenProbeInProgress;
        private long generation;

        synchronized CircuitAdmission tryAllow(long nowNanos) {
            if (openUntilNanos == 0L) {
                return new CircuitAdmission(generation, false);
            }
            if (nowNanos < openUntilNanos || halfOpenProbeInProgress) {
                return null;
            }
            // 冷却结束后仅放行一个探测请求，避免故障恢复时出现瞬时流量回灌。
            halfOpenProbeInProgress = true;
            return new CircuitAdmission(generation, true);
        }

        synchronized boolean releaseHalfOpenProbe(CircuitAdmission admission) {
            if (admission.generation() == generation && admission.halfOpenProbe()) {
                halfOpenProbeInProgress = false;
            }
            return openUntilNanos != 0L;
        }

        synchronized boolean recordSuccess(CircuitAdmission admission) {
            if (admission.generation() != generation) return openUntilNanos != 0L;
            if (admission.halfOpenProbe()) {
                consecutiveFailures = 0;
                openUntilNanos = 0L;
                halfOpenProbeInProgress = false;
                return false;
            }
            if (openUntilNanos == 0L) {
                consecutiveFailures = 0;
                return false;
            }
            return true;
        }

        synchronized boolean recordFailure(CircuitAdmission admission, int failureThreshold, long openDurationNanos) {
            // 每次打开均作废上一代许可，旧请求不能释放新探测位、重置失败计数或重新熔断。
            if (admission.generation() != generation) return openUntilNanos != 0L;
            if (admission.halfOpenProbe() || ++consecutiveFailures >= failureThreshold) {
                generation++;
                openUntilNanos = System.nanoTime() + openDurationNanos;
                halfOpenProbeInProgress = false;
                return true;
            }
            return false;
        }
    }
}
