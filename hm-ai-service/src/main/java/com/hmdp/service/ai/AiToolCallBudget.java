package com.hmdp.service.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 将一次客户问答的工具调用预算绑定到 LangChain4j 的工具执行器。
 */
@Component
public class AiToolCallBudget {

    private static final int TOOL_RESULT_TOKEN_RESERVE = (AiCustomerTools.MAX_TOOL_RESULT_CODE_POINTS + 1) / 2;

    private final ThreadLocal<AiExecutionBudget> currentBudget = new ThreadLocal<>();
    private final int maxOutputTokens;

    public AiToolCallBudget(@Value("${langchain4j.openai.max-tokens:512}") int maxOutputTokens) {
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("langchain4j.openai.max-tokens 必须大于 0");
        }
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * 同步 Agent 调用期间绑定预算；调用结束后恢复上层上下文，避免线程复用污染后续请求。
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

    /**
     * 工具真正执行前预扣一次工具调用及下一轮模型预算，超限时阻止工具访问内部 API。
     */
    public String executeTool(ToolExecutionRequest request, Object memoryId, ToolExecutor delegate) {
        Objects.requireNonNull(request, "tool 请求不能为空");
        Objects.requireNonNull(delegate, "tool 执行器不能为空");
        AiExecutionBudget budget = currentBudget.get();
        if (budget != null
                && (!budget.tryConsumeToolCall()
                || !budget.tryConsumeTokens(nextModelRoundTokenReserve(1)))) {
            throw new AiToolCallBudgetExceededException();
        }
        return delegate.execute(request, memoryId);
    }

    /**
     * 工具执行后 LangChain4j 会再次请求模型；按受限的工具输出和最大回复保守预留下一轮预算。
     */
    private int nextModelRoundTokenReserve(int requestedToolCalls) {
        long reserve = (long) maxOutputTokens + (long) requestedToolCalls * TOOL_RESULT_TOKEN_RESERVE;
        return reserve > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) reserve;
    }
}
