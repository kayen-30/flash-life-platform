package com.hmdp.service.ai;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 将一次客户问答的工具调用预算绑定到 Spring AI 的内部工具编排循环。
 */
@Component
public class AiToolCallBudget implements ToolExecutionEligibilityPredicate {

    private static final int TOOL_RESULT_TOKEN_RESERVE = (AiCustomerTools.MAX_TOOL_RESULT_CODE_POINTS + 1) / 2;

    private final ToolExecutionEligibilityPredicate delegate = new DefaultToolExecutionEligibilityPredicate();
    private final ThreadLocal<AiExecutionBudget> currentBudget = new ThreadLocal<>();
    private final int maxOutputTokens;

    public AiToolCallBudget(@Value("${spring.ai.openai.chat.options.max-tokens:512}") int maxOutputTokens) {
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("spring.ai.openai.chat.options.max-tokens 必须大于 0");
        }
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * 同步 ChatClient 调用期间绑定预算；调用结束后恢复上层上下文，避免线程复用污染后续请求。
     */
    public <T> T executeWithBudget(AiExecutionBudget budget, Supplier<T> action) {
        Objects.requireNonNull(budget, "budget 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        AiExecutionBudget previous = currentBudget.get();
        currentBudget.set(budget);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                currentBudget.remove();
            } else {
                currentBudget.set(previous);
            }
        }
    }

    @Override
    public boolean test(ChatOptions promptOptions, ChatResponse chatResponse) {
        if (!delegate.test(promptOptions, chatResponse)) {
            return false;
        }
        AiExecutionBudget budget = currentBudget.get();
        if (budget == null) {
            return true;
        }
        int requestedToolCalls = chatResponse.getResults().stream()
                .mapToInt(generation -> generation.getOutput().getToolCalls() == null
                        ? 0 : generation.getOutput().getToolCalls().size())
                .sum();
        if (requestedToolCalls > 0) {
            if (!budget.tryConsumeToolCalls(requestedToolCalls)
                    || !budget.tryConsumeTokens(nextModelRoundTokenReserve(requestedToolCalls))) {
                throw new AiToolCallBudgetExceededException();
            }
        }
        return requestedToolCalls > 0;
    }

    /**
     * 工具执行后 Spring AI 会再次请求模型；按受限的工具输出和最大回复保守预留下一轮预算。
     */
    private int nextModelRoundTokenReserve(int requestedToolCalls) {
        long reserve = (long) maxOutputTokens + (long) requestedToolCalls * TOOL_RESULT_TOKEN_RESERVE;
        return reserve > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) reserve;
    }
}
