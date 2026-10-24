package com.hmdp.ai.knowledge;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 受限资料使用仍有效的登录身份；请求开始时的身份快照不能延长已撤销的会话。 */
@Slf4j
@Service
class KnowledgePrincipal {
    private static final String LOGIN_USER_KEY = "login:token:";
    private final StringRedisTemplate redisTemplate;

    KnowledgePrincipal(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 令牌仅用于本次校验，不写入用户DTO、会话历史、模型输入或日志。 */
    UserDTO currentUser() {
        UserDTO trusted = UserHolder.getUser();
        if (trusted == null || trusted.getId() == null || trusted.getId() <= 0
                || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        try {
            String token = attributes.getRequest().getHeader("authorization");
            if (token == null || token.isBlank()) return null;
            Object sessionUserId = redisTemplate.opsForHash().get(LOGIN_USER_KEY + token, "id");
            // 登录态必须绑定网关已经认证的同一用户；其他账号的有效令牌不能补足权限。
            return trusted.getId().toString().equals(sessionUserId) ? trusted : null;
        } catch (RuntimeException unavailable) {
            // 无法确认登录态时仅撤去受限权限，禁止携带令牌的Redis异常进入日志。
            log.warn("受限知识的登录态无法确认");
            return null;
        }
    }
}
