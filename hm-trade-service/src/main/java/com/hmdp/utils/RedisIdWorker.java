package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Redis全局唯一ID生成器，使用时间戳和Redis自增序列拼接出64位ID。
 */
@Component
public class RedisIdWorker {

    /**
     * 自定义起始时间戳：2022-01-01 00:00:00 UTC。
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号占用32位，支持同一秒内生成2^32个不同ID。
     */
    private static final int COUNT_BITS = 32;
    private static final long COUNTER_TTL_DAYS = 2L;
    private static final DateTimeFormatter ID_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 按业务类型生成全局唯一ID，ID结构为：符号位0 + 时间戳31位 + 序列号32位。
     */
    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();
        // 时间戳部分使用相对秒数，减少ID长度并保证ID整体递增。
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 序列号按业务和日期分桶，既隔离不同业务，也便于后续按天统计或清理。
        String date = now.format(ID_DATE_FORMATTER);
        String counterKey = "incr:" + keyPrefix + ":" + date;
        long count = stringRedisTemplate.opsForValue().increment(counterKey);
        if (count == 1L) {
            // 日计数器只在首次创建时设置过期，避免每次发号都额外刷新 TTL。
            stringRedisTemplate.expire(counterKey, COUNTER_TTL_DAYS, TimeUnit.DAYS);
        }

        // 时间戳左移后与序列号拼接，低32位保留给Redis当天自增计数。
        return timestamp << COUNT_BITS | count;
    }
}
