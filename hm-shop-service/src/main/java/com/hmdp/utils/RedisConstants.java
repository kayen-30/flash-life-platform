package com.hmdp.utils;

/**
 * 店铺缓存和 GEO 索引统一使用 shop 命名空间。
 */
public final class RedisConstants {
    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long CACHE_REBUILD_LOCK_TTL = 10L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType:list";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;
    public static final String SHOP_GEO_KEY = "shop:geo:";

    private RedisConstants() {
    }
}
