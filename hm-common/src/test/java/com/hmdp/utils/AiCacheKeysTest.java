package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiCacheKeysTest {

    @Test
    void shouldBuildSameShopRecommendationKeysForBothServices() {
        assertEquals("cache:ai:shop-recommend:12", AiCacheKeys.shopRecommendKey(12L));
        assertEquals("lock:ai:shop-recommend:12", AiCacheKeys.shopRecommendLockKey(12L));
    }
}
