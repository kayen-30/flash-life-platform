package com.hmdp.service;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 统一校验 AI 调用用户，并通过 Redis 限制单用户的模型请求频率。
 */
@Component
public class AiRequestGuard {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("lua/ai_rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public AiRequestGuard(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

    /**
     * 固定窗口内原子计数，避免并发请求绕过额度限制。
     */
    public boolean tryAcquire(String scene, Long userId, int limit) {
        Long result = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                List.of("ai:rate:" + scene + ":" + userId),
                "60",
                String.valueOf(limit)
        );
        return Long.valueOf(1L).equals(result);
    }
}
