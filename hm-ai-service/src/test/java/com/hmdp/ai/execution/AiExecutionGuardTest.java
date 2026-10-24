package com.hmdp.ai.execution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiExecutionGuardTest {

    private AiExecutionProperties properties;
    private AiExecutionGuard guard;

    @BeforeEach
    void setUp() {
        properties = new AiExecutionProperties();
        properties.setMaxConcurrentRequests(1);
        properties.setAcquireTimeout(Duration.ZERO);
        properties.setCircuitFailureThreshold(2);
        properties.setCircuitOpenDuration(Duration.ofSeconds(30));
        properties.setMaxToolCalls(1);
        properties.setTokenBudgetPerRequest(3);
        guard = new AiExecutionGuard(properties, new AiExecutionMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void shouldRejectWhenConcurrentLimitIsReached() {
        try (AiExecutionGuard.ExecutionPermit first = guard.tryAcquire("chat")) {
            assertThat(first.isGranted()).isTrue();

            AiExecutionGuard.ExecutionPermit second = guard.tryAcquire("chat");
            assertThat(second.isGranted()).isFalse();
            assertThat(second.getRejectionReason()).isEqualTo(AiExecutionRejectionReason.CONCURRENCY_LIMIT);

            first.success();
        }
    }

    @Test
    void shouldOpenCircuitAfterConsecutiveFailures() {
        assertThatThrownBy(() -> guard.execute("chat", () -> {
            throw new IllegalStateException("model unavailable");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.execute("chat", () -> {
            throw new IllegalStateException("model unavailable");
        })).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> guard.execute("chat", () -> "should not run"))
                .isInstanceOfSatisfying(AiExecutionRejectedException.class,
                        exception -> assertThat(exception.getReason()).isEqualTo(AiExecutionRejectionReason.CIRCUIT_OPEN));
    }

    @Test
    void wrappedLocalBudgetDeadlineAndAdmissionRejectionsDoNotPoisonTheCircuit() {
        properties.setCircuitFailureThreshold(1);
        guard = new AiExecutionGuard(properties, new AiExecutionMetrics(new SimpleMeterRegistry()));
        for (RuntimeException failure : java.util.List.of(new IllegalStateException(new AiDeadlineExceededException()),
                new IllegalStateException(new com.hmdp.ai.agent.tool.AiToolCallBudgetExceededException()),
                new IllegalStateException(new AiExecutionRejectedException(AiExecutionRejectionReason.CONCURRENCY_LIMIT)),
                new IllegalArgumentException("invalid caller parameter"))) {
            assertThatThrownBy(() -> guard.execute("chat", () -> { throw failure; })).isSameAs(failure);
            assertThat(guard.execute("chat", () -> "still available")).isEqualTo("still available");
        }
    }

    @Test
    void shouldTrackPerRequestToolAndTokenBudget() {
        AiExecutionBudget budget = guard.newBudget("chat");

        assertThat(budget.tryConsumeToolCall()).isTrue();
        assertThat(budget.tryConsumeToolCall()).isFalse();
        assertThat(budget.tryConsumeTokens(2)).isTrue();
        assertThat(budget.tryConsumeTokens(2)).isFalse();
        assertThat(budget.getRemainingToolCalls()).isZero();
        assertThat(budget.getRemainingTokens()).isEqualTo(1);
    }

    @Test
    void rejectedModelAdmissionDoesNotConsumeAnUnmadeCallOrTokens() {
        AiExecutionBudget budget = guard.newBudget("chat");
        assertThatThrownBy(() -> budget.reserveModelCall(4, Duration.ZERO))
                .isInstanceOf(com.hmdp.ai.agent.tool.AiToolCallBudgetExceededException.class);
        assertThat(budget.getModelCalls()).isZero();
        assertThat(budget.getRemainingTokens()).isEqualTo(3);

        budget.reserveModelCall(1, Duration.ZERO);
        assertThat(budget.getModelCalls()).isEqualTo(1);
        assertThat(budget.getRemainingTokens()).isEqualTo(2);
        assertThatThrownBy(() -> budget.reserveModelCall(0, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(budget.getModelCalls()).isEqualTo(1);
        assertThat(budget.getRemainingTokens()).isEqualTo(2);

        properties.setMaxModelCalls(1);
        AiExecutionBudget singleCall = new AiExecutionGuard(properties, new AiExecutionMetrics(new SimpleMeterRegistry())).newBudget("chat");
        singleCall.reserveModelCall(1, Duration.ZERO);
        assertThatThrownBy(() -> singleCall.reserveModelCall(1, Duration.ZERO))
                .isInstanceOf(com.hmdp.ai.agent.tool.AiToolCallBudgetExceededException.class);
        assertThat(singleCall.getModelCalls()).isEqualTo(1);
        assertThat(singleCall.getRemainingTokens()).isEqualTo(2);
    }

    @Test
    void lateClosedFailureCannotReleaseAnActiveRecoveryProbe() throws InterruptedException {
        properties.setMaxConcurrentRequests(3);
        properties.setCircuitFailureThreshold(1);
        properties.setCircuitOpenDuration(Duration.ofMillis(1));
        guard = new AiExecutionGuard(properties, new AiExecutionMetrics(new SimpleMeterRegistry()));
        try (var late = guard.tryAcquire("chat")) {
            assertThat(late.isGranted()).isTrue();
            try (var trigger = guard.tryAcquire("chat")) {
                trigger.failure(new IllegalStateException("open circuit"));
            }
            Thread.sleep(10);
            try (var probe = guard.tryAcquire("chat")) {
                assertThat(probe.isGranted()).isTrue();
                late.failure(new IllegalStateException("old request returns late"));
                Thread.sleep(10);
                try (var next = guard.tryAcquire("chat")) {
                    assertThat(next.isGranted()).isFalse();
                    assertThat(next.getRejectionReason()).isEqualTo(AiExecutionRejectionReason.CIRCUIT_OPEN);
                }
                probe.success();
            }
        }
    }

    @Test
    void lateClosedFailureCannotReopenARecoveredCircuit() throws InterruptedException {
        properties.setMaxConcurrentRequests(3);
        properties.setCircuitFailureThreshold(1);
        properties.setCircuitOpenDuration(Duration.ofMillis(1));
        guard = new AiExecutionGuard(properties, new AiExecutionMetrics(new SimpleMeterRegistry()));
        try (var late = guard.tryAcquire("chat")) {
            try (var trigger = guard.tryAcquire("chat")) {
                trigger.failure(new IllegalStateException("open circuit"));
            }
            Thread.sleep(10);
            try (var probe = guard.tryAcquire("chat")) {
                assertThat(probe.isGranted()).isTrue();
                probe.success();
            }
            late.failure(new IllegalStateException("old generation must be ignored"));
            try (var next = guard.tryAcquire("chat")) {
                assertThat(next.isGranted()).isTrue();
                next.success();
            }
        }
    }

    @Test
    void shouldReleaseHalfOpenProbeAfterNonTransientFailure() throws InterruptedException {
        properties.setCircuitFailureThreshold(1);
        properties.setCircuitOpenDuration(Duration.ofMillis(1));
        guard = new AiExecutionGuard(properties, new AiExecutionMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> guard.execute("chat", () -> {
            throw new IllegalStateException("model unavailable");
        })).isInstanceOf(IllegalStateException.class);
        Thread.sleep(10);

        assertThatThrownBy(() -> guard.execute("chat", () -> {
            throw new IllegalArgumentException("invalid request");
        })).isInstanceOf(IllegalArgumentException.class);

        try (AiExecutionGuard.ExecutionPermit permit = guard.tryAcquire("chat")) {
            assertThat(permit.isGranted()).isTrue();
            permit.success();
        }
    }

    @Test
    void shouldReleaseHalfOpenProbeWhenPermitIsClosedWithoutOutcome() throws InterruptedException {
        properties.setCircuitFailureThreshold(1);
        properties.setCircuitOpenDuration(Duration.ofMillis(1));
        guard = new AiExecutionGuard(properties, new AiExecutionMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> guard.execute("chat", () -> {
            throw new IllegalStateException("model unavailable");
        })).isInstanceOf(IllegalStateException.class);
        Thread.sleep(10);

        try (AiExecutionGuard.ExecutionPermit permit = guard.tryAcquire("chat")) {
            assertThat(permit.isGranted()).isTrue();
        }

        try (AiExecutionGuard.ExecutionPermit nextPermit = guard.tryAcquire("chat")) {
            assertThat(nextPermit.isGranted()).isTrue();
            nextPermit.success();
        }
    }
}
