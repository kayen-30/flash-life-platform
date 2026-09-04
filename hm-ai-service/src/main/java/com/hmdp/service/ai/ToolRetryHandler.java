package com.hmdp.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 工具调用重试处理器。一次模型工具调用最多重试三次，退避时间为 100ms、200ms、400ms。
 */
@Slf4j
@Component
public class ToolRetryHandler {

    private static final int MAX_RETRIES = 3;
    private static final int MAX_ATTEMPTS = MAX_RETRIES + 1;
    private static final long BASE_BACKOFF_MS = 100L;
    private static final String FAILURE_MARKER = "调用失败（已重试";

    /**
     * 只重试可能由服务抖动导致的运行时异常；预算拒绝和参数错误直接返回或抛出。
     */
    public String executeWithRetry(Supplier<String> action, String toolName) {
        if (action == null) {
            throw new IllegalArgumentException("工具执行逻辑不能为空");
        }
        String normalizedToolName = normalizeToolName(toolName);
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String result = action.get();
                if (attempt > 1) {
                    log.info("工具调用成功（第 {} 次尝试），工具={}", attempt, normalizedToolName);
                }
                return result;
            } catch (RuntimeException exception) {
                lastException = exception;
                log.warn("工具调用失败（第 {} 次），工具={}, 错误={}",
                        attempt, normalizedToolName, rootCause(exception));

                // 预算异常需要交回上层统一降级，不能被重试掩盖或继续消耗预算。
                if (exception instanceof AiToolCallBudgetExceededException) {
                    throw exception;
                }
                if (exception instanceof IllegalArgumentException) {
                    return failureMessage(normalizedToolName, attempt, exception);
                }
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }

                long backoffMs = BASE_BACKOFF_MS * (1L << (attempt - 1));
                log.info("重试中...（等待 {}ms），工具={}", backoffMs, normalizedToolName);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    log.warn("工具重试等待被中断，工具={}", normalizedToolName);
                    return failureMessage(normalizedToolName, attempt,
                            new IllegalStateException("重试等待被中断", interruptedException));
                }
            }
        }
        return failureMessage(normalizedToolName, MAX_ATTEMPTS, lastException);
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
