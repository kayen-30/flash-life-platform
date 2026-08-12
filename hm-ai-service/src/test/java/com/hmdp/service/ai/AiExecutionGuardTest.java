package com.hmdp.service.ai;

import com.hmdp.config.AiExecutionProperties;
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
