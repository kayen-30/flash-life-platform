package com.hmdp.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 按用户隔离短期会话记忆，Redis 不可用时降级为本轮对话，避免会话能力影响客服可用性。
 */
@Slf4j
@Service
public class AiConversationService {

    private static final String KEY_PREFIX = "ai:conversation:";
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AiPrivacySanitizer privacySanitizer;

    @Value("${hmdp.ai.conversation.ttl:30m}")
    private Duration conversationTtl;

    @Value("${hmdp.ai.conversation.max-messages:12}")
    private int maxMessages;

    @Value("${hmdp.ai.conversation.max-message-chars:1200}")
    private int maxMessageChars;

    public AiConversationService(StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper,
                                 AiPrivacySanitizer privacySanitizer) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.privacySanitizer = privacySanitizer;
    }

    /**
     * 解析或创建会话，并读取已持久化的有限历史，避免历史无限增长占用上下文窗口。
     */
    public Conversation open(Long userId, String requestedConversationId) {
        String conversationId = normalizeConversationId(requestedConversationId);
        return new Conversation(conversationId, load(userId, conversationId));
    }

    /** 读取短期上下文；一轮对话包含一条用户消息和一条客服消息。 */
    public List<ConversationMessage> getRecentMessages(Long userId, String conversationId, int rounds) {
        if (rounds <= 0) {
            return List.of();
        }
        List<ConversationMessage> messages = load(userId, conversationId);
        int maxMessages = Math.max(2, rounds * 2);
        int start = Math.max(0, messages.size() - maxMessages);
        return List.copyOf(messages.subList(start, messages.size()));
    }

    public void appendUser(Long userId, String conversationId, String message) {
        append(userId, conversationId, "user", message);
    }

    public void appendAssistant(Long userId, String conversationId, String message) {
        append(userId, conversationId, "assistant", message);
    }

    private List<ConversationMessage> load(Long userId, String conversationId) {
        try {
            List<String> rows = stringRedisTemplate.opsForList().range(key(userId, conversationId), 0, -1);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<ConversationMessage> messages = new ArrayList<>(rows.size());
            for (String row : rows) {
                try {
                    ConversationMessage message = objectMapper.readValue(row, ConversationMessage.class);
                    if (message != null && isRoleSupported(message.role()) && message.content() != null) {
                        messages.add(message);
                    }
                } catch (JsonProcessingException ignored) {
                    // 单条脏数据不影响整段会话恢复。
                }
            }
            return List.copyOf(messages);
        } catch (RuntimeException e) {
            log.warn("读取 AI 会话失败，userId={}, conversationId={}", userId, conversationId, e);
            return List.of();
        }
    }

    private void append(Long userId, String conversationId, String role, String content) {
        String cleanContent = privacySanitizer.limit(privacySanitizer.sanitizeForModel(content), maxMessageChars);
        if (cleanContent.isBlank()) {
            return;
        }
        try {
            String key = key(userId, conversationId);
            stringRedisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(
                    new ConversationMessage(role, cleanContent)
            ));
            // 只保留最近消息，保证可预测的 Token 消耗和 Redis 占用。
            stringRedisTemplate.opsForList().trim(key, -Math.max(maxMessages, 2), -1);
            stringRedisTemplate.expire(key, conversationTtl);
        } catch (JsonProcessingException e) {
            log.warn("序列化 AI 会话失败，userId={}, conversationId={}", userId, conversationId, e);
        } catch (RuntimeException e) {
            log.warn("保存 AI 会话失败，userId={}, conversationId={}", userId, conversationId, e);
        }
    }

    private String normalizeConversationId(String requestedConversationId) {
        if (requestedConversationId != null && CONVERSATION_ID_PATTERN.matcher(requestedConversationId).matches()) {
            return requestedConversationId;
        }
        return UUID.randomUUID().toString();
    }

    private String key(Long userId, String conversationId) {
        return KEY_PREFIX + userId + ':' + conversationId;
    }

    private boolean isRoleSupported(String role) {
        return "user".equals(role) || "assistant".equals(role);
    }

    public record Conversation(String id, List<ConversationMessage> history) {
    }

    public record ConversationMessage(String role, String content) {
    }
}
