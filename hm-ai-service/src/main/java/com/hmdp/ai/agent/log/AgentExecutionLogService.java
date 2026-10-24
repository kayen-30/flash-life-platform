package com.hmdp.ai.agent.log;

import com.hmdp.ai.agent.tool.ToolRetryHandler;
import com.hmdp.ai.security.AiPrivacySanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * ReAct 工具执行日志服务。按用户、会话与请求隔离，避免迟到写入覆盖其他轮次。
 */
@Slf4j
@Service
public class AgentExecutionLogService {

    private static final String LOG_KEY_PREFIX = "ai:react:logs:";
    private static final int LOG_TTL_HOURS = 2;
    private static final int MAX_INPUT_CODE_POINTS = 500;
    private static final int MAX_OBSERVATION_CODE_POINTS = 1200;
    private static final int MAX_ERROR_CODE_POINTS = 500;
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile(
            "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AiPrivacySanitizer privacySanitizer;
    private final ThreadLocal<ConversationLogContext> contextHolder = new ThreadLocal<>();

    public AgentExecutionLogService(StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    AiPrivacySanitizer privacySanitizer) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.privacySanitizer = privacySanitizer;
    }

    /**
     * 开始绑定当前同步请求的 ReAct 日志上下文；工具执行完成后由 finishLogging 清理线程变量。
     */
    public void startLogging(Long userId, String conversationId, String requestId) {
        if (userId == null || userId <= 0 || !isValidConversationId(conversationId) || !isValidRequestId(requestId)) {
            contextHolder.remove();
            return;
        }
        contextHolder.set(new ConversationLogContext(userId, conversationId, requestId));
        log.debug("开始记录 ReAct 日志，conversationId={}, requestId={}", conversationId, requestId);
    }

    /**
     * 包装实际 ToolExecutor，统一记录参数、结果、耗时和失败状态。
     */
    public String executeToolCall(String toolName, String input, Supplier<String> action) {
        return executeToolCall(toolName, input, action, Function.identity());
    }

    /** 保留原始工具结果和框架属性；内部 Skill 正文只发给模型，日志使用脱敏摘要。 */
    public <T> T executeToolCall(String toolName, String input, Supplier<T> action, Function<T, String> observationText) {
        Objects.requireNonNull(action, "工具执行逻辑不能为空");
        long startedAtNanos = System.nanoTime();
        String observation = null;
        String status = "SUCCESS";
        String errorMessage = null;
        try {
            T result = action.get();
            observation = observationText.apply(result);
            if (ToolRetryHandler.isFailureMessage(observation)) {
                status = "FAILED";
                errorMessage = observation;
            }
            return result;
        } catch (RuntimeException | Error exception) {
            status = "FAILED";
            errorMessage = clean(exception.getMessage(), MAX_ERROR_CODE_POINTS);
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = exception.getClass().getSimpleName();
            }
            observation = "工具调用失败：" + errorMessage;
            throw exception;
        } finally {
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
            logToolCall(toolName, input, observation, duration, status, errorMessage);
        }
    }

    /**
     * 将当前请求的步骤整体写入 Redis；Redis 异常只影响可视化，不影响客服主流程。
     */
    public void finishLogging() {
        ConversationLogContext context = contextHolder.get();
        if (context == null) {
            return;
        }
        try {
            String key = key(context.userId(), context.conversationId(), context.requestId);
            String json = objectMapper.writeValueAsString(context.logs());
            redisTemplate.opsForValue().set(key, json, LOG_TTL_HOURS, TimeUnit.HOURS);
            log.info("ReAct 日志已保存，conversationId={}, requestId={}, stepCount={}",
                    context.conversationId(), context.requestId, context.logs().size());
        } catch (Exception exception) {
            log.warn("保存 ReAct 日志失败，conversationId={}, requestId={}", context.conversationId(), context.requestId, exception);
        } finally {
            contextHolder.remove();
        }
    }

    /**
     * 精确读取本轮记录；缺失时保持为空，禁止回退到另一轮或另一用户的日志。
     */
    public List<AgentExecutionLog> getLogs(Long userId, String conversationId, String requestId) {
        if (userId == null || userId <= 0 || !isValidConversationId(conversationId) || !isValidRequestId(requestId)) {
            return List.of();
        }
        try {
            String json = redisTemplate.opsForValue().get(key(userId, conversationId, requestId));
            if (json == null || json.isBlank()) {
                return List.of();
            }
            List<AgentExecutionLog> logs = objectMapper.readValue(
                    json, new TypeReference<List<AgentExecutionLog>>() { });
            return logs == null ? List.of() : List.copyOf(logs);
        } catch (Exception exception) {
            log.warn("读取 ReAct 日志失败，conversationId={}, requestId={}", conversationId, requestId, exception);
            return List.of();
        }
    }

    private void logToolCall(String toolName, String input, String observation,
                             long duration, String status, String errorMessage) {
        ConversationLogContext context = contextHolder.get();
        if (context == null) {
            log.debug("未绑定 ReAct 日志上下文，跳过工具日志，toolName={}", toolName);
            return;
        }
        int step = context.nextStep();
        AgentExecutionLog entry = AgentExecutionLog.builder()
                .requestId(context.requestId)
                .step(step)
                .thought(describeAction(toolName, step))
                .action(clean(toolName, 80))
                .actionInput(clean(input, MAX_INPUT_CODE_POINTS))
                .observation(clean(observation, MAX_OBSERVATION_CODE_POINTS))
                .duration(Math.max(0L, duration))
                .status(status)
                .errorMessage(clean(errorMessage, MAX_ERROR_CODE_POINTS))
                .timestamp(LocalDateTime.now())
                .build();
        context.logs().add(entry);
        log.info("ReAct 工具调用：requestId={}, step={}, action={}, status={}, duration={}ms",
                context.requestId, step, toolName, status, duration);
    }

    private String describeAction(String toolName, int step) {
        if (toolName == null) {
            return "第 " + step + " 步：执行未命名工具";
        }
        return switch (toolName) {
            case "query_shop_by_name" -> "按名称查询店铺基础信息（服务端步骤说明）";
            case "query_shop_vouchers" -> "查询店铺当前优惠券（服务端步骤说明）";
            case "query_hot_blogs" -> "查询平台热门探店笔记（服务端步骤说明）";
            case "query_shop_blogs" -> "查询店铺探店笔记（服务端步骤说明）";
            default -> "第 " + step + " 步：调用工具 " + toolName;
        };
    }

    private String clean(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        return privacySanitizer.limit(privacySanitizer.sanitizeForModel(value), maxCodePoints);
    }

    private boolean isValidConversationId(String conversationId) {
        return conversationId != null && CONVERSATION_ID_PATTERN.matcher(conversationId).matches();
    }

    private boolean isValidRequestId(String requestId) {
        return requestId != null && REQUEST_ID_PATTERN.matcher(requestId).matches();
    }

    private String key(Long userId, String conversationId, String requestId) {
        return LOG_KEY_PREFIX + userId + ":" + conversationId + ":" + requestId;
    }

    private static final class ConversationLogContext {
        private final Long userId;
        private final String conversationId;
        private final String requestId;
        private final List<AgentExecutionLog> logs = new ArrayList<>();
        private int currentStep;

        private ConversationLogContext(Long userId, String conversationId, String requestId) {
            this.userId = userId;
            this.conversationId = conversationId;
            this.requestId = requestId;
        }

        private int nextStep() {
            return ++currentStep;
        }

        private Long userId() {
            return userId;
        }

        private String conversationId() {
            return conversationId;
        }

        private List<AgentExecutionLog> logs() {
            return logs;
        }
    }
}
