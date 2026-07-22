package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheClientTest {

    private CacheClient cacheClient;

    @AfterEach
    void tearDown() {
        if (cacheClient != null) {
            ReflectionTestUtils.invokeMethod(cacheClient, "shutdownCacheRebuildExecutor");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void coldCacheUsesMutexBeforeLoadingDatabase() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:shop:1")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("lock:shop:1"), any(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        cacheClient = new CacheClient(redisTemplate);
        Shop shop = new Shop().setId(1L).setName("测试店铺");
        Function<Long, Shop> dbFallback = mock(Function.class);
        when(dbFallback.apply(1L)).thenReturn(shop);

        Shop result = cacheClient.queryWithLogicalExpire(
                "cache:shop:", "lock:shop:", 1L, Shop.class,
                dbFallback, 30L, TimeUnit.MINUTES
        );

        assertEquals(shop, result);
        verify(valueOperations).setIfAbsent(eq("lock:shop:1"), any(), eq(10L), eq(TimeUnit.SECONDS));
        verify(dbFallback).apply(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void coldCacheWritesNullSentinelWhenDatabaseHasNoRecord() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:shop:99")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("lock:shop:99"), any(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        cacheClient = new CacheClient(redisTemplate);
        Function<Long, Shop> dbFallback = mock(Function.class);

        Shop result = cacheClient.queryWithLogicalExpire(
                "cache:shop:", "lock:shop:", 99L, Shop.class,
                dbFallback, 30L, TimeUnit.MINUTES
        );

        assertNull(result);
        verify(valueOperations).set("cache:shop:99", "", CacheConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
        verify(dbFallback).apply(99L);
        verify(redisTemplate, never()).delete("cache:shop:99");
    }

    @Test
    @SuppressWarnings("unchecked")
    void coldCacheWaiterUsesRebuiltValueWithoutLoadingDatabase() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        Shop shop = new Shop().setId(1L).setName("其他线程重建的店铺");
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(30));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cache:shop:1"))
                .thenReturn(null, JSONUtil.toJsonStr(redisData));
        when(valueOperations.setIfAbsent(eq("lock:shop:1"), any(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);
        cacheClient = new CacheClient(redisTemplate);
        Function<Long, Shop> dbFallback = mock(Function.class);

        Shop result = cacheClient.queryWithLogicalExpire(
                "cache:shop:", "lock:shop:", 1L, Shop.class,
                dbFallback, 30L, TimeUnit.MINUTES
        );

        assertEquals(shop, result);
        verify(dbFallback, never()).apply(1L);
    }
}
