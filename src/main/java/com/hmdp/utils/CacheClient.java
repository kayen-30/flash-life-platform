package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Redis缓存工具类，统一封装普通缓存、缓存穿透和逻辑过期重建。
 */
@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
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
        if (StrUtil.isBlank(json)) {
            // 首次查询允许回源，避免必须提前预热缓存。
            R r = dbFallback.apply(id);
            if (r == null) {
                return null;
            }
            setWithLogicalExpire(key, r, time, unit);
            return r;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean(JSONUtil.parseObj(redisData.getData()), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            // 逻辑未过期时直接返回，保证热点数据的读取性能。
            return r;
        }

        String lockKey = lockPrefix + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 只有一个线程负责重建缓存，其他线程继续返回旧值，避免请求堆积。
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R fresh = dbFallback.apply(id);
                    if (fresh != null) {
                        setWithLogicalExpire(key, fresh, time, unit);
                    }
                } finally {
                    unlock(lockKey);
                }
            });
        }

        // 逻辑过期后先返回旧值，等后台线程重建完成再切换到新数据。
        return r;
    }

    /**
     * 通过setnx获取互斥锁，避免多个线程同时重建同一个key。
     */
    private boolean tryLock(String key) {
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                "1",
                RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS
        );
        return BooleanUtil.isTrue(success);
    }

    /**
     * 缓存重建结束后立即释放锁，让后续请求恢复正常流程。
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
