package com.hmdp.config;

import com.hmdp.utils.CacheClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 店铺服务显式启用公共 Redis 缓存客户端。
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheClient cacheClient(StringRedisTemplate stringRedisTemplate) {
        return new CacheClient(stringRedisTemplate);
    }
}
