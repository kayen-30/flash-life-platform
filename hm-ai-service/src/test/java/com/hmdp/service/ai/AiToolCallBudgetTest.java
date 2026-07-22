package com.hmdp.service.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiToolCallBudgetTest {

    private final AiToolCallBudget toolCallBudget = new AiToolCallBudget(512);

    @Test
    void shouldReserveNextModelRoundWhenToolCallIsRequested() {
        AiExecutionBudget budget = newBudget(1, 1112);

        boolean toolExecutionRequired = toolCallBudget.executeWithBudget(budget,
                () -> toolCallBudget.test(toolOptions(), toolCallResponse()));

        assertThat(toolExecutionRequired).isTrue();
        assertThat(budget.getRemainingToolCalls()).isZero();
        assertThat(budget.getRemainingTokens()).isZero();
    }

    @Test
    void shouldRejectToolExecutionWhenNextModelRoundCannotBeReserved() {
        AiExecutionBudget budget = newBudget(1, 1111);

        assertThatThrownBy(() -> toolCallBudget.executeWithBudget(budget,
                () -> toolCallBudget.test(toolOptions(), toolCallResponse())))
                .isInstanceOf(AiToolCallBudgetExceededException.class);
    }

    private AiExecutionBudget newBudget(int toolCalls, int tokens) {
        return new AiExecutionBudget("chat", toolCalls, tokens,
                new AiExecutionMetrics(new SimpleMeterRegistry()));
    }

    private OpenAiChatOptions toolOptions() {
        return OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(true)
                .build();
    }

    private ChatResponse toolCallResponse() {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("tool-call-1", "function", "query_shop", "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
