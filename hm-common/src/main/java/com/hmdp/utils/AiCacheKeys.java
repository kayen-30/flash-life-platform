package com.hmdp.utils;

/**
 * AI 服务与业务服务共用的缓存键，避免店铺更新遗漏 AI 生成内容的失效。
 */
public final class AiCacheKeys {

    public static final String SHOP_RECOMMEND_PREFIX = "cache:ai:shop-recommend:";
    public static final String SHOP_RECOMMEND_LOCK_PREFIX = "lock:ai:shop-recommend:";

    private AiCacheKeys() {
    }

    public static String shopRecommendKey(Long shopId) {
        return SHOP_RECOMMEND_PREFIX + shopId;
    }

    public static String shopRecommendLockKey(Long shopId) {
        return SHOP_RECOMMEND_LOCK_PREFIX + shopId;
    }
}
