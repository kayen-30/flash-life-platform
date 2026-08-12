package com.hmdp.mq;

import com.hmdp.config.ShopSearchMqConstants;
import com.hmdp.service.IShopSearchService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 消费成功后才确认消息，重复消费会按商铺 ID 幂等覆盖 ES 文档。
 */
@Component
public class ShopSearchSyncListener {

    private final IShopSearchService shopSearchService;

    public ShopSearchSyncListener(IShopSearchService shopSearchService) {
        this.shopSearchService = shopSearchService;
    }

    @RabbitListener(
            queues = ShopSearchMqConstants.QUEUE,
            containerFactory = ShopSearchMqConstants.LISTENER_FACTORY)
    public void syncShop(ShopSyncMessage message) {
        if (message == null || message.shopId() == null) {
            throw new IllegalArgumentException("商铺索引同步消息缺少 shopId");
        }
        shopSearchService.syncShop(message.shopId());
    }
}
