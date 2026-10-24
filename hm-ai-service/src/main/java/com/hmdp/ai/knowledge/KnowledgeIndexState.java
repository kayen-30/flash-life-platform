package com.hmdp.ai.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** Redis只协调索引批次；文档原文与权限始终以发布清单为准。 */
@Component
@Slf4j
class KnowledgeIndexState {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local ready = redis.call('GET', KEYS[2])
            if ready and string.match(ready, '^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') then return 0 end
            if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', 30000) then return 1 end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> READY = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[1])
            redis.call('DEL', KEYS[1], KEYS[3], KEYS[4])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> FAILED = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            local failures = math.min(redis.call('INCR', KEYS[3]), 10)
            redis.call('PEXPIRE', KEYS[3], 86400000)
            local delay = math.min(30000 * (2 ^ (failures - 1)), 300000)
            redis.call('SET', KEYS[2], '1', 'PX', delay)
            redis.call('DEL', KEYS[1])
            return delay
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    @Value("${milvus.knowledge-collection:shop_knowledge}")
    private String collection;

    KnowledgeIndexState(StringRedisTemplate redis) { this.redis = redis; }

    /** 状态丢失或不可读时仅用当前授权关键词，不猜测哪个索引批次已完整。 */
    String readyBuild(String publication) {
        try {
            String value = redis.opsForValue().get(key("ready", publication));
            return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    ? value.toLowerCase(java.util.Locale.ROOT) : null;
        } catch (RuntimeException failure) {
            log.warn("AI_KNOWLEDGE_INDEX phase=STATE_UNAVAILABLE, causeType={}", failure.getClass().getSimpleName());
            return null;
        }
    }

    /** 同一集合一次只构建一个批次；失败冷却按发布版本共享，新发布无需等待旧版本冷却。 */
    boolean acquire(String publication, String build) {
        return Long.valueOf(1).equals(redis.execute(ACQUIRE,
                List.of(leaseKey(), key("ready", publication), key("retry", publication)), build));
    }

    /** 租约已过期或被新实例接替时，旧构建不能发布就绪指针。 */
    boolean ready(String publication, String build) {
        return Long.valueOf(1).equals(redis.execute(READY, List.of(leaseKey(), key("ready", publication),
                key("retry", publication), key("failures", publication)), build));
    }

    void failed(String publication, String build) {
        Long delay = redis.execute(FAILED, List.of(leaseKey(), key("retry", publication), key("failures", publication)), build);
        log.info("AI_KNOWLEDGE_INDEX phase=RETRY_SCHEDULED, publication={}, retryAfterMs={}", publication, delay);
    }

    void release(String build) { redis.execute(RELEASE, List.of(leaseKey()), build); }

    private String leaseKey() { return key("lease", ""); }
    // 同集合的Lua键使用相同hash tag，迁移到Redis Cluster时仍能在同一槽执行。
    private String key(String kind, String publication) { return "ai:knowledge:index:{" + collection + "}:" + kind + ':' + publication; }
}
