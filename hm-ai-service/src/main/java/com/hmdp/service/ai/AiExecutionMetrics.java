package com.hmdp.service.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一记录 AI 调用指标；scene 必须来自代码常量，不能使用用户输入，以控制标签基数。
 */
@Component
public class AiExecutionMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ConcurrentMap<String, AtomicInteger> circuitStates = new ConcurrentHashMap<>();

    public AiExecutionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("hmdp.ai.execution.inflight", inFlight, AtomicInteger::get)
                .description("正在执行的 AI 外部调用数")
                .register(meterRegistry);
    }

    public void executionStarted() {
        inFlight.incrementAndGet();
    }

    public void executionFinished(String scene, String outcome, Duration duration) {
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
        Counter.builder("hmdp.ai.execution.calls")
                .tag("scene", scene)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
        Timer.builder("hmdp.ai.execution.duration")
                .tag("scene", scene)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }

    public void rejected(String scene, AiExecutionRejectionReason reason) {
        Counter.builder("hmdp.ai.execution.rejected")
                .tag("scene", scene)
                .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
    }

    public void budgetConsumed(String scene, String budgetType, int amount) {
        DistributionSummary.builder("hmdp.ai.execution.budget.consumed")
                .tag("scene", scene)
                .tag("type", budgetType)
                .register(meterRegistry)
                .record(amount);
    }

    public void budgetExhausted(String scene, String budgetType) {
        Counter.builder("hmdp.ai.execution.budget.exhausted")
                .tag("scene", scene)
                .tag("type", budgetType)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 模型响应可取得 usage 时调用，记录实际 Token 用量而非预算预扣量。
     */
    public void modelTokens(String scene, int totalTokens) {
        if (totalTokens <= 0) {
            return;
        }
        DistributionSummary.builder("hmdp.ai.model.tokens")
                .tag("scene", scene)
                .register(meterRegistry)
                .record(totalTokens);
    }

    public void circuitState(String scene, boolean open) {
        AtomicInteger state = circuitStates.computeIfAbsent(scene, this::registerCircuitGauge);
        state.set(open ? 1 : 0);
    }

    private AtomicInteger registerCircuitGauge(String scene) {
        AtomicInteger state = new AtomicInteger();
        Gauge.builder("hmdp.ai.execution.circuit.open", state, AtomicInteger::get)
                .tag("scene", scene)
                .register(meterRegistry);
        return state;
    }
}
