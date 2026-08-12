package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Redis缓存工具类，统一封装普通缓存、缓存穿透和逻辑过期重建。
 */
public class CacheClient {

    private static final long CACHE_REBUILD_RETRY_DELAY_MS = 50L;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private final ExecutorService cacheRebuildExecutor = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 写入普通缓存，适合固定时间失效的数据。
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 写入逻辑过期缓存，过期后仍保留旧值，便于后台异步重建热点数据。
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 先查缓存，缓存空时回源数据库；数据库也为空时写空值，避免缓存穿透。
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 命中过期空值，直接返回，避免反复穿透数据库。
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(
                    key,
                    "",
                    CacheConstants.CACHE_NULL_TTL,
                    TimeUnit.MINUTES
            );
            return null;
        }

        set(key, r, time, unit);
        return r;
    }

    /**
     * 先查逻辑过期缓存，过期后优先返回旧值，再用互斥锁异步重建。
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix,
            String lockPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            // 冷缓存也通过互斥锁回源，避免并发请求同时压到数据库。
            return loadColdCache(key, lockPrefix + id, id, type, dbFallback, time, unit);
        }
        if (StrUtil.isBlank(json)) {
            // 空值占位表示数据库中不存在，短时间内不再回源。
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 逻辑未过期时直接返回，保证热点数据的读取性能。
            return r;
        }

        String lockKey = lockPrefix + id;
        String lockValue = tryLock(lockKey);
        if (lockValue != null) {
            // 只有一个线程负责重建缓存，其他线程继续返回旧值，避免请求堆积。
            cacheRebuildExecutor.submit(() -> {
                try {
                    R fresh = dbFallback.apply(id);
                    if (fresh != null) {
                        setWithLogicalExpire(key, fresh, time, unit);
                    }
                } finally {
                    unlock(lockKey, lockValue);
                }
            });
        }

        // 逻辑过期后先返回旧值，等后台线程重建完成再切换到新数据。
        return r;
    }

    /**
     * 冷缓存只允许一个线程查询数据库，其他线程等待缓存或空值占位写入。
     */
    private <R, ID> R loadColdCache(
            String key,
            String lockKey,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit unit
    ) {
        while (true) {
            String lockValue = tryLock(lockKey);
            if (lockValue == null) {
                waitForCacheRebuild();
                String json = stringRedisTemplate.opsForValue().get(key);
                if (json != null) {
                    return readLogicalCacheValue(json, type);
                }
                continue;
            }

            try {
                // 拿锁后再次检查，避免等待期间其他线程已经完成缓存重建。
                String json = stringRedisTemplate.opsForValue().get(key);
                if (json != null) {
                    return readLogicalCacheValue(json, type);
                }

                R value = dbFallback.apply(id);
                if (value == null) {
                    stringRedisTemplate.opsForValue().set(
                            key,
                            "",
                            CacheConstants.CACHE_NULL_TTL,
                            TimeUnit.MINUTES
                    );
                    return null;
                }
                setWithLogicalExpire(key, value, time, unit);
                return value;
            } finally {
                unlock(lockKey, lockValue);
            }
        }
    }

    /**
     * 读取其他线程刚写入的逻辑缓存，空字符串表示数据库中无记录。
     */
    private <R> R readLogicalCacheValue(String json, Class<R> type) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        return JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), type);
    }

    /**
     * 未获得冷缓存锁时短暂等待，避免持续轮询 Redis 占用 CPU。
     */
    private void waitForCacheRebuild() {
        try {
            Thread.sleep(CACHE_REBUILD_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待缓存重建时被中断", e);
        }
    }

    /**
     * 通过setnx获取互斥锁，避免多个线程同时重建同一个key。
     */
    private String tryLock(String key) {
        String lockValue = UUID.randomUUID().toString();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                lockValue,
                CacheConstants.CACHE_REBUILD_LOCK_TTL,
                TimeUnit.SECONDS
        );
        return BooleanUtil.isTrue(success) ? lockValue : null;
    }

    /**
     * 仅释放当前任务持有的锁，避免锁过期后误删其他线程新获得的锁。
     */
    private void unlock(String key, String lockValue) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, List.of(key), lockValue);
    }

    /**
     * Spring 容器关闭时终止缓存重建线程，避免应用上下文销毁后仍持有资源。
     */
    @PreDestroy
    private void shutdownCacheRebuildExecutor() {
        cacheRebuildExecutor.shutdownNow();
    }
}
