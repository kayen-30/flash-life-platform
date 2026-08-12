package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mq.ShopSearchSyncPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class ShopServiceImplTest {

    @Test
    void updateReturnsFailureAndKeepsCacheWhenShopDoesNotExist() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ShopSearchSyncPublisher searchSyncPublisher = mock(ShopSearchSyncPublisher.class);
        ShopServiceImpl shopService = spy(new ShopServiceImpl());
        ReflectionTestUtils.setField(shopService, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(shopService, "shopSearchSyncPublisher", searchSyncPublisher);
        Shop shop = new Shop().setId(99L).setName("不存在的店铺");
        doReturn(false).when(shopService).updateById(shop);

        Result result = shopService.update(shop);

        assertFalse(result.getSuccess());
        verify(redisTemplate, never()).delete(CACHE_SHOP_KEY + 99L);
        verify(searchSyncPublisher, never()).publish(99L);
    }

    @Test
    void updateDeletesCacheAfterDatabaseUpdateSucceeds() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ShopSearchSyncPublisher searchSyncPublisher = mock(ShopSearchSyncPublisher.class);
        ShopServiceImpl shopService = spy(new ShopServiceImpl());
        ReflectionTestUtils.setField(shopService, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(shopService, "shopSearchSyncPublisher", searchSyncPublisher);
        Shop shop = new Shop().setId(1L).setName("更新后的店铺");
        doReturn(true).when(shopService).updateById(shop);

        Result result = shopService.update(shop);

        assertTrue(result.getSuccess());
        verify(redisTemplate).delete(CACHE_SHOP_KEY + 1L);
        verify(searchSyncPublisher).publish(1L);
    }
}
