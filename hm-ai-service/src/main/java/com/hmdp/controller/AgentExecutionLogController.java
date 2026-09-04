package com.hmdp.controller;

import com.hmdp.dto.AgentExecutionLog;
import com.hmdp.dto.Result;
import com.hmdp.service.AiRequestGuard;
import com.hmdp.service.ai.AgentExecutionLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
     * 查询会话最近一次 Agent 执行的工具步骤；未登录或会话不存在时不返回其他用户数据。
     */
    @GetMapping("/{conversationId}")
    @Operation(summary = "查询会话的执行日志", description = "返回当前用户该会话的工具调用步骤")
    public Result getLogs(@PathVariable("conversationId") String conversationId) {
        Long userId = requestGuard.currentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(logService.getLogs(userId, conversationId));
    }
}
