package com.hmdp.service;

import com.hmdp.config.RabbitMqConstants;
import com.hmdp.entity.VoucherOrder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/**
 * 秒杀订单生产者异步执行首次投递，发布异常由 Redis 待发布任务继续补偿。
 */
@Slf4j
@Component
public class VoucherOrderPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final TaskExecutor orderPublishExecutor;

    public VoucherOrderPublisher(RabbitTemplate rabbitTemplate,
                                 @Qualifier(RabbitMqConstants.ORDER_PUBLISH_EXECUTOR)
                                 TaskExecutor orderPublishExecutor) {
        this.rabbitTemplate = rabbitTemplate;
        this.orderPublishExecutor = orderPublishExecutor;
    }

    /**
     * HTTP 请求只提交任务；提交或投递失败时保留 pending order，等待定时任务重投。
     */
    public void publishAsync(VoucherOrder order) {
        try {
            orderPublishExecutor.execute(() -> {
                try {
                    publish(order);
                } catch (RuntimeException e) {
                    log.warn("首次投递失败，等待定时任务重试，orderId={}", order.getId(), e);
                }
            });
        } catch (RuntimeException e) {
            // 有界队列饱和时不能回压 Tomcat 线程，pending order 会在后续扫描中恢复。
            log.warn("首次投递任务提交失败，等待定时任务重试，orderId={}", order.getId(), e);
        }
    }

    /**
     * 定时重投保留同步调用，以便发送异常时不刷新待发布记录的扫描时间。
     */
    public void publish(VoucherOrder order) {
        rabbitTemplate.convertAndSend(
                RabbitMqConstants.ORDER_EXCHANGE,
                RabbitMqConstants.ORDER_ROUTING_KEY,
                order,
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                }
        );
    }
}
