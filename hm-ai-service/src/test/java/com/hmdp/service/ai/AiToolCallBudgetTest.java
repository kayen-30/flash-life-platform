package com.hmdp.service.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import dev.langchain4j.agent.tool.ToolExecutionRequest;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiToolCallBudgetTest {

    private final AiToolCallBudget toolCallBudget = new AiToolCallBudget(512);

    @Test
    void shouldReserveNextModelRoundWhenToolCallIsRequested() {
        AiExecutionBudget budget = newBudget(1, 1112);

        boolean toolExecutionRequired = toolCallBudget.executeWithBudget(budget,
                () -> {
                    toolCallBudget.executeTool(toolRequest(), null, (request, memoryId) -> "ok");
                    return true;
                });

        assertThat(toolExecutionRequired).isTrue();
        assertThat(budget.getRemainingToolCalls()).isZero();
        assertThat(budget.getRemainingTokens()).isZero();
    }

    @Test
    void shouldRejectToolExecutionWhenNextModelRoundCannotBeReserved() {
        AiExecutionBudget budget = newBudget(1, 1111);

        assertThatThrownBy(() -> toolCallBudget.executeWithBudget(budget,
                () -> toolCallBudget.executeTool(toolRequest(), null, (request, memoryId) -> "ok")))
                .isInstanceOf(AiToolCallBudgetExceededException.class);
    }

    private AiExecutionBudget newBudget(int toolCalls, int tokens) {
        return new AiExecutionBudget("chat", toolCalls, tokens,
                new AiExecutionMetrics(new SimpleMeterRegistry()));
    }

    private ToolExecutionRequest toolRequest() {
        return ToolExecutionRequest.builder()
                .id("tool-call-1")
                .name("query_shop")
                .arguments("{}")
                .build();
    }
}
