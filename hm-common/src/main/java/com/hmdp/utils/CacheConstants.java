package com.hmdp.utils;

/**
 * 各业务服务共用的缓存控制参数。
 */
public final class CacheConstants {
    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long CACHE_REBUILD_LOCK_TTL = 10L;

    private CacheConstants() {
    }
}
