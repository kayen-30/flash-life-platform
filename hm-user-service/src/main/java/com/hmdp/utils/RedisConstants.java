package com.hmdp.utils;

/**
 * 用户服务独占的登录和签到 Redis key。
 */
public final class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_CODE_COOLDOWN_KEY = "login:code:cooldown:";
    public static final Long LOGIN_CODE_COOLDOWN_SECONDS = 60L;
    public static final String LOGIN_ATTEMPT_KEY = "login:attempt:";
    public static final int LOGIN_MAX_ATTEMPTS = 5;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL_SECONDS = 36000L;
    public static final String USER_SIGN_KEY = "sign:";

    private RedisConstants() {
    }
}
