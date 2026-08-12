package com.hmdp.service.ai;

import com.hmdp.config.AiExecutionProperties;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    private final AiExecutionMetrics metrics;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

    public AiExecutionGuard(AiExecutionProperties properties, AiExecutionMetrics metrics) {
        this.concurrentRequests = new Semaphore(requirePositive(properties.getMaxConcurrentRequests(), "max-concurrent-requests"), true);
        this.acquireTimeoutNanos = requireNonNegative(properties.getAcquireTimeout(), "acquire-timeout").toNanos();
        this.circuitFailureThreshold = requirePositive(properties.getCircuitFailureThreshold(), "circuit-failure-threshold");
        this.circuitOpenDurationNanos = requirePositive(properties.getCircuitOpenDuration(), "circuit-open-duration").toNanos();
        this.maxToolCalls = requirePositive(properties.getMaxToolCalls(), "max-tool-calls");
        this.tokenBudgetPerRequest = requirePositive(properties.getTokenBudgetPerRequest(), "token-budget-per-request");
        this.metrics = metrics;
    }

    /**
     * 包装同步模型调用，自动完成并发释放、熔断状态更新与耗时指标记录。
     */
    public <T> T execute(String scene, Supplier<T> action) {
        Objects.requireNonNull(action, "action 不能为空");
        try (ExecutionPermit permit = tryAcquire(scene)) {
            if (!permit.isGranted()) {
                throw new AiExecutionRejectedException(permit.getRejectionReason());
            }
            try {
                T result = action.get();
                permit.success();
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
        if (admission == CircuitAdmission.REJECTED) {
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
            circuit.releaseHalfOpenProbe(admission == CircuitAdmission.HALF_OPEN);
            metrics.rejected(normalizedScene, AiExecutionRejectionReason.CONCURRENCY_LIMIT);
            return rejectedPermit(normalizedScene, AiExecutionRejectionReason.CONCURRENCY_LIMIT);
        }

        metrics.executionStarted();
        return grantedPermit(normalizedScene, circuit, admission == CircuitAdmission.HALF_OPEN);
    }

    public AiExecutionBudget newBudget(String scene) {
        return new AiExecutionBudget(requireScene(scene), maxToolCalls, tokenBudgetPerRequest, metrics);
    }

    private ExecutionPermit rejectedPermit(String scene, AiExecutionRejectionReason reason) {
        return new ExecutionPermit(scene, null, false, reason, 0L);
    }

    private ExecutionPermit grantedPermit(String scene, CircuitState circuit, boolean halfOpenProbe) {
        return new ExecutionPermit(scene, circuit, halfOpenProbe, null, System.nanoTime());
    }

    private boolean shouldCountCircuitFailure(Throwable exception) {
        // 参数错误、预算拒绝和模型 4xx 不代表模型服务不可用，不能据此打开熔断器。
        return !(exception instanceof AiExecutionRejectedException)
                && !(exception instanceof IllegalArgumentException)
                && !(exception instanceof NonTransientAiException);
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
        private final boolean halfOpenProbe;
        private final AiExecutionRejectionReason rejectionReason;
        private final long startedAtNanos;
        private boolean completed;
        private boolean closed;

        private ExecutionPermit(String scene, CircuitState circuit, boolean halfOpenProbe,
                                AiExecutionRejectionReason rejectionReason, long startedAtNanos) {
            this.scene = scene;
            this.circuit = circuit;
            this.halfOpenProbe = halfOpenProbe;
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
                    circuitOpen = circuit.recordSuccess(halfOpenProbe);
                } else if (countCircuitFailure) {
                    circuitOpen = circuit.recordFailure(halfOpenProbe, circuitFailureThreshold, circuitOpenDurationNanos);
                } else {
                    // 4xx 等非服务故障不能卡住半开探测，释放后允许下一次真实模型调用继续验证恢复情况。
                    circuitOpen = circuit.releaseHalfOpenProbe(halfOpenProbe);
                }
            }
            if (exception == null || countCircuitFailure || halfOpenProbe) {
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
                boolean circuitOpen = circuit.releaseHalfOpenProbe(halfOpenProbe);
                if (halfOpenProbe) {
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

    private enum CircuitAdmission {
        CLOSED,
        HALF_OPEN,
        REJECTED
    }

    private static final class CircuitState {

        private int consecutiveFailures;
        private long openUntilNanos;
        private boolean halfOpenProbeInProgress;

        synchronized CircuitAdmission tryAllow(long nowNanos) {
            if (openUntilNanos == 0L) {
                return CircuitAdmission.CLOSED;
            }
            if (nowNanos < openUntilNanos || halfOpenProbeInProgress) {
                return CircuitAdmission.REJECTED;
            }
            // 冷却结束后仅放行一个探测请求，避免故障恢复时出现瞬时流量回灌。
            halfOpenProbeInProgress = true;
            return CircuitAdmission.HALF_OPEN;
        }

        synchronized boolean releaseHalfOpenProbe(boolean halfOpenProbe) {
            if (halfOpenProbe) {
                halfOpenProbeInProgress = false;
            }
            return openUntilNanos != 0L;
        }

        synchronized boolean recordSuccess(boolean halfOpenProbe) {
            if (halfOpenProbe) {
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

        synchronized boolean recordFailure(boolean halfOpenProbe, int failureThreshold, long openDurationNanos) {
            if (halfOpenProbe || openUntilNanos != 0L) {
                openUntilNanos = System.nanoTime() + openDurationNanos;
                halfOpenProbeInProgress = false;
                return true;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) {
                openUntilNanos = System.nanoTime() + openDurationNanos;
                return true;
            }
            return false;
        }
    }
}
