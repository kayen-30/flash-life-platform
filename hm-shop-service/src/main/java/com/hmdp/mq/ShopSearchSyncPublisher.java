package com.hmdp.mq;

import com.hmdp.config.ShopSearchMqConstants;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 在 MySQL 事务提交后发布持久化商铺索引同步消息。
 */
@Component
public class ShopSearchSyncPublisher {

    private final RabbitTemplate rabbitTemplate;

    public ShopSearchSyncPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(Long shopId) {
        rabbitTemplate.convertAndSend(
                ShopSearchMqConstants.EXCHANGE,
                ShopSearchMqConstants.ROUTING_KEY,
                new ShopSyncMessage(shopId),
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                });
    }
}
