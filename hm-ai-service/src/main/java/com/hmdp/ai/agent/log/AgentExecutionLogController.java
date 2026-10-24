package com.hmdp.ai.agent.log;

import com.hmdp.ai.security.AiRequestGuard;
import com.hmdp.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供当前用户自己的 Agent ReAct 步骤查询接口。
 */
@RestController
@RequestMapping({"/agent/logs", "/api/agent/logs"})
@Tag(name = "Agent 执行日志", description = "ReAct 可视化接口")
public class AgentExecutionLogController {

    private final AgentExecutionLogService logService;
    private final AiRequestGuard requestGuard;

    public AgentExecutionLogController(AgentExecutionLogService logService,
                                       AiRequestGuard requestGuard) {
        this.logService = logService;
        this.requestGuard = requestGuard;
    }

    /**
     * 用响应中的requestId精确查询执行步骤，避免取到上一轮或并发轮次的记录。
     */
    @GetMapping("/{conversationId}")
    @Operation(summary = "查询请求的执行日志", description = "传入task.requestId，返回当前用户该会话本次请求的工具步骤")
    public Result getLogs(@PathVariable("conversationId") String conversationId, @RequestParam("requestId") String requestId) {
        Long userId = requestGuard.currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(logService.getLogs(userId, conversationId, requestId));
    }
}
