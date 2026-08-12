package com.hmdp.utils;

/**
 * 交易服务独占的活动缓存、库存和购买资格 key。
 */
public final class RedisConstants {
    public static final Long CACHE_SECKILL_VOUCHER_TTL = 30L;
    public static final Long SECKILL_KEY_RETENTION_HOURS = 48L;
    public static final String CACHE_SECKILL_VOUCHER_KEY = "cache:seckill:voucher:";
    public static final String LOCK_SECKILL_VOUCHER_KEY = "lock:seckill:voucher:";
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:v2:";
    public static final String SECKILL_LEGACY_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_PENDING_ORDER_KEY = "seckill:pending:order:";
    public static final String SECKILL_PENDING_ORDER_INDEX_KEY = "seckill:pending:orders";
    public static final String SECKILL_INVALID_PENDING_ORDER_KEY = "seckill:pending:invalid";

    private RedisConstants() {
    }
}
