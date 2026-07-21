package com.hmdp.service;

import com.hmdp.config.RabbitMqConstants;
import com.hmdp.entity.VoucherOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 秒杀订单生产者等待 Broker 确认，发布结果未知时由 Redis 待发布任务继续投递。
 */
@Component
public class VoucherOrderPublisher {

    private static final long CONFIRM_TIMEOUT_MS = 3000L;

    private final RabbitTemplate rabbitTemplate;

    public VoucherOrderPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(VoucherOrder order) {
        CorrelationData correlationData = new CorrelationData(order.getId().toString());
        rabbitTemplate.convertAndSend(
                RabbitMqConstants.ORDER_EXCHANGE,
                RabbitMqConstants.ORDER_ROUTING_KEY,
                order,
                message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                },
                correlationData
        );
        waitForBrokerConfirmation(order, correlationData);
    }

    /**
     * 同时校验 Broker confirm 和 mandatory return，避免交换机确认但消息没有路由到队列。
     */
    private void waitForBrokerConfirmation(VoucherOrder order, CorrelationData correlationData) {
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new AmqpException("秒杀订单消息未路由到队列，orderId=" + order.getId()
                        + ", replyText=" + returned.getReplyText());
            }
            if (!confirm.isAck()) {
                throw new AmqpException("Broker 拒绝秒杀订单消息，orderId=" + order.getId()
                        + ", reason=" + confirm.getReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("等待秒杀订单发布确认时线程被中断，orderId=" + order.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AmqpException("等待秒杀订单发布确认失败，orderId=" + order.getId(), e);
        }
    }
}
