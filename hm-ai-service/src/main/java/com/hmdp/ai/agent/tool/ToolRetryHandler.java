package com.hmdp.ai.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 工具失败统一转换为可追踪结果；请求内不做隐式网络重试。
 */
@Slf4j
@Component
public class ToolRetryHandler {

    private static final String FAILURE_MARKER = "调用失败（已重试";

    /** 客服只读工具先执行一次；失败如实返回，避免隐式重试耗尽总时限。 */
    public String executeWithRetry(Supplier<String> action, String toolName) {
        if (action == null) throw new IllegalArgumentException("工具执行逻辑不能为空");
        try {
            return action.get();
        } catch (com.hmdp.ai.execution.AiDeadlineExceededException | AiToolCallBudgetExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // SDK反射调用会包裹业务异常；预算/截止时间必须继续中止整轮请求。
            for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof AiToolCallBudgetExceededException exceeded) throw exceeded;
                if (cause instanceof com.hmdp.ai.execution.AiDeadlineExceededException deadline) throw deadline;
            }
            return failureMessage(normalizeToolName(toolName), 1, exception);
        }
    }

    /** ReAct 日志用此标记判断“友好失败文本”应记录为 FAILED。 */
    public static boolean isFailureMessage(String result) {
        return result != null && result.contains(FAILURE_MARKER);
    }

    private String failureMessage(String toolName, int attempts, Throwable exception) {
        String message = rootCause(exception);
        int retryCount = Math.max(0, attempts - 1);
        String result = "工具 " + toolName + " 调用失败（已重试 " + retryCount + " 次，共尝试 "
                + attempts + " 次）：" + message + "。请尝试其他方案或告知用户稍后再试。";
        log.error("工具调用最终失败，toolName={}, attempts={}, retries={}",
                toolName, attempts, retryCount);
        return result;
    }

    private String rootCause(Throwable exception) {
        if (exception == null) {
            return "未知错误";
        }
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        String compact = message.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    private String normalizeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "unknown_tool";
        }
        return toolName.length() <= 80 ? toolName : toolName.substring(0, 80);
    }
}
