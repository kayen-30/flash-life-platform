package com.hmdp.ai.agent.tool;

import com.hmdp.ai.execution.AiExecutionBudget;
import com.hmdp.ai.skill.AiBusinessSkills;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** 工具只扣次数；模型输入与输出在真实 chat 边界统一计量。 */
@Component
public class AiToolCallBudget {
    public <T> T executeWithBudget(AiExecutionBudget budget, Supplier<T> action) {
        return Objects.requireNonNull(budget).call(action);
    }

    public String executeTool(ToolExecutionRequest request, Object memoryId, ToolExecutor delegate) {
        return executeTool(request, Duration.ofSeconds(4), () -> delegate.execute(request, memoryId));
    }

    /** Skill 管理工具是内存操作，仍扣工具次数，但不预留远程业务工具的 4 秒网络时间。 */
    public <T> T executeTool(ToolExecutionRequest request, Duration requiredTime, Supplier<T> action) {
        AiBusinessSkills.requireToolAllowed(request.name());
        AiExecutionBudget budget = AiExecutionBudget.current();
        if (budget != null) {
            // Feign connect/read 分别为1s/3s，保留完整尝试时间。
            budget.requireTime(requiredTime);
            if (!budget.tryConsumeToolCall()) throw new AiToolCallBudgetExceededException();
        }
        T result = action.get();
        if (budget != null) budget.requireTime(Duration.ZERO);
        return result;
    }
}
