package com.hmdp.ai.memory;

import com.hmdp.ai.security.AiPrivacySanitizer;
import com.hmdp.ai.customer.CustomerTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.hmdp.ai.execution.AiExecutionBudget;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 按用户隔离会话和已确认任务；Redis不可用时拒绝本轮，避免猜测上下文继续执行。
 */
@Slf4j
@Service
public class AiConversationService {

    private static final String KEY_PREFIX = "ai:conversation:";
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> COMMIT = new DefaultRedisScript<>("""
            if redis.call('get',KEYS[3]) ~= ARGV[1] then return 0 end
            redis.call('rpush',KEYS[1],ARGV[2],ARGV[3])
            redis.call('ltrim',KEYS[1],-tonumber(ARGV[4]),-1)
            redis.call('pexpire',KEYS[1],ARGV[5])
            redis.call('set',KEYS[2],ARGV[6],'PX',ARGV[5])
            return 1
            """, Long.class);
    private final ThreadLocal<Turn> activeTurn = new ThreadLocal<>();

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectReader storedJsonReader;
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
        // 仅读取会话时收紧协议，不改变应用共享ObjectMapper的行为。
        this.storedJsonReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.privacySanitizer = privacySanitizer;
    }

    /** 配置必须能保留完整问答对，且在任何Redis写入之前具有有效的毫秒TTL。 */
    @PostConstruct
    void validateConfiguration() {
        try {
            if (conversationTtl == null || conversationTtl.toMillis() <= 0 || maxMessages < 2
                    || maxMessages % 2 != 0 || maxMessageChars <= 0) throw new IllegalArgumentException();
            Math.addExact(System.currentTimeMillis(), conversationTtl.toMillis());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("会话配置无效：TTL须为正毫秒，消息数须为至少2的偶数，消息字符上限须为正数", invalid);
        }
    }

    /** 同一用户会话一次只处理一轮，避免旧请求覆盖新店铺；Redis 故障时不猜上下文。 */
    public Turn beginTurn(Long userId, String requestedId) {
        String id = normalizeConversationId(requestedId);
        String owner = UUID.randomUUID().toString();
        String lockKey = key(userId, id) + ":lock";
        AiExecutionBudget budget = AiExecutionBudget.current();
        Duration lease = budget == null ? Duration.ofSeconds(35) : budget.remainingTime().plusSeconds(10);
        boolean acquired;
        try {
            acquired = Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(lockKey, owner, lease));
        } catch (RuntimeException exception) {
            throw new ConversationUnavailableException(exception);
        }
        if (!acquired) {
            throw new ConversationBusyException();
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(key(userId, id) + ":state");
            // 旧会话只有店铺字段；首次提交时升级，不删除已有上下文。
            SessionState state = readState(json);
            Turn turn = new Turn(userId, new Conversation(id, load(userId, id)), owner, state.shop(), state.task());
            activeTurn.set(turn);
            return turn;
        } catch (Exception exception) {
            ConversationUnavailableException failure = exception instanceof ConversationUnavailableException unavailable
                    ? unavailable : new ConversationUnavailableException(exception);
            try {
                stringRedisTemplate.execute(RELEASE, List.of(lockKey), owner);
            } catch (RuntimeException releaseFailure) {
                // 清理故障不能覆盖首次读取故障；锁仍由租约兜底释放。
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    /** 兼容已知旧店铺结构；JSON null、空对象或未知格式不能伪装成新会话。 */
    private SessionState readState(String json) throws JsonProcessingException {
        if (json == null) return new SessionState(null, null);
        JsonNode node = readObject(json);
        if (node.has("shop")) return objectMapper.treeToValue(node, SessionState.class);
        if (node.has("shopId")) return new SessionState(objectMapper.treeToValue(node, ShopState.class), null);
        throw new IllegalArgumentException("会话状态格式无法识别");
    }

    private JsonNode readObject(String json) throws JsonProcessingException {
        JsonNode node = storedJsonReader.readTree(json);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("会话记录须为JSON对象");
        return node;
    }

    public ShopState currentShop() {
        Turn turn = activeTurn.get();
        return turn == null ? null : turn.shop;
    }

    /** 只允许业务 DTO 或已验证页面实体更新状态，不从生成文本反向提取ID。 */
    public void confirmShop(Long shopId, String name, String source) {
        Turn turn = activeTurn.get();
        if (turn == null || shopId == null || shopId <= 0) return;
        if (turn.shop != null && !Objects.equals(shopId, turn.shop.shopId())) turn.task = null;
        long revision = turn.shop == null ? 1 : turn.shop.revision() + 1;
        String safeName = privacySanitizer.limit(privacySanitizer.sanitizeForModel(name), 80);
        turn.shop = new ShopState(shopId, safeName, source, System.currentTimeMillis(), revision, false);
    }

    /** 查新目标失败或出现歧义时清除旧店，禁止偷偷回退到上一家。 */
    public void clearShop(boolean ambiguous) {
        Turn turn = activeTurn.get();
        if (turn != null) {
            long revision = turn.shop == null ? 1 : turn.shop.revision() + 1;
            turn.shop = new ShopState(null, null, "unresolved", System.currentTimeMillis(), revision, ambiguous);
            turn.task = null;
        }
    }

    public record ShopState(Long shopId, String shopName, String resolvedFrom,
                            long updatedAt, long revision, boolean ambiguous) { }
    public record SessionState(ShopState shop, CustomerTask.State task) { }

    public static class ConversationBusyException extends IllegalArgumentException {
        public ConversationBusyException() { super("本会话上一轮仍在处理中，请稍后再试"); }
    }

    public static class ConversationUnavailableException extends IllegalStateException {
        public ConversationUnavailableException(Throwable cause) {
            super("会话状态暂时无法确认，请稍后再试", cause);
        }
    }

    public final class Turn implements AutoCloseable {
        private final Long userId;
        private final Conversation conversation;
        private final String owner;
        private ShopState shop;
        private CustomerTask.State task;

        private Turn(Long userId, Conversation conversation, String owner, ShopState shop, CustomerTask.State task) {
            this.userId = userId;
            this.conversation = conversation;
            this.owner = owner;
            this.shop = shop;
            this.task = task;
        }

        public Conversation conversation() { return conversation; }
        public ShopState shop() { return shop; }
        public CustomerTask.State task() { return task; }

        /** 与问答、店铺一起原子保存；失败请求不落下半份新约束。 */
        public void task(CustomerTask plan) {
            task = new CustomerTask.State(shop == null ? null : shop.shopId(), shop == null ? null : shop.shopName(),
                    plan.capabilities(), plan.constraints(), plan.missing(), task == null ? 1 : task.revision() + 1);
        }

        /** 问答和实体在同一个 Lua 中落库；失去租约或已截止的迟到答案不写成功历史。 */
        public void commit(String question, String answer) {
            AiExecutionBudget budget = AiExecutionBudget.current();
            if (budget != null) budget.requireTime(Duration.ofSeconds(2));
            String base = key(userId, conversation.id());
            try {
                ShopState state = shop == null ? new ShopState(null, null, "none", 0, 0, false) : shop;
                Long committed = stringRedisTemplate.execute(COMMIT,
                        List.of(base, base + ":state", base + ":lock"), owner,
                        objectMapper.writeValueAsString(new ConversationMessage("user", cleanMessage(question))),
                        objectMapper.writeValueAsString(new ConversationMessage("assistant", cleanMessage(answer))),
                        Integer.toString(maxMessages), Long.toString(conversationTtl.toMillis()),
                        objectMapper.writeValueAsString(new SessionState(state, task)));
                if (!Long.valueOf(1).equals(committed)) throw new ConversationBusyException();
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("会话序列化失败", exception);
            } catch (ConversationBusyException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                // 提交超时可能已在 Redis 执行，不能宣称未写入，也不能自动重试追加。
                throw new ConversationUnavailableException(exception);
            }
        }

        @Override
        public void close() {
            activeTurn.remove();
            try {
                stringRedisTemplate.execute(RELEASE, List.of(key(userId, conversation.id()) + ":lock"), owner);
            } catch (RuntimeException exception) {
                log.warn("会话锁释放失败，等待租约过期，conversationId={}", conversation.id());
            }
        }
    }

    private String cleanMessage(String content) {
        return privacySanitizer.limit(privacySanitizer.sanitizeForModel(content), maxMessageChars);
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

    /** 任一历史行损坏都拒绝读取；不得跳过后用不完整上下文继续生成答案。 */
    private List<ConversationMessage> load(Long userId, String conversationId) {
        try {
            List<String> rows = stringRedisTemplate.opsForList().range(key(userId, conversationId), 0, -1);
            if (rows == null || rows.isEmpty()) return List.of();
            if (rows.size() % 2 != 0) throw new IllegalArgumentException("会话历史缺少完整问答对");
            List<ConversationMessage> messages = new ArrayList<>(rows.size());
            for (String row : rows) {
                JsonNode node = readObject(row);
                // 唯一写入口整轮保存user+assistant；乱序或半轮历史不能当作完整上下文。
                String expectedRole = messages.size() % 2 == 0 ? "user" : "assistant";
                if (node.size() != 2 || !node.path("role").isTextual() || !node.path("content").isTextual()
                        || !expectedRole.equals(node.path("role").textValue()) || node.path("content").textValue().isBlank()) {
                    throw new IllegalArgumentException("会话历史字段无效");
                }
                messages.add(new ConversationMessage(node.path("role").textValue(), node.path("content").textValue()));
            }
            return List.copyOf(messages);
        } catch (Exception exception) {
            throw new ConversationUnavailableException(exception);
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

    public record Conversation(String id, List<ConversationMessage> history) {
    }

    public record ConversationMessage(String role, String content) {
    }
}
