package com.hmdp.service;

import com.hmdp.config.RabbitMqConstants;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
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
 * 订单落库成功后发送延迟取消消息，到期时检查支付状态并决定是否取消。
 */
@Slf4j
@Component
public class OrderCancelPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;

    private final RabbitTemplate rabbitTemplate;

    public OrderCancelPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送延迟取消消息，delayMs 毫秒后由消费者检查订单是否超时未支付。
     */
    public void publishDelayed(VoucherOrder order, int delayMs) {
        CorrelationData correlationData = new CorrelationData(order.getId().toString());
        rabbitTemplate.convertAndSend(
                RabbitMqConstants.ORDER_CANCEL_EXCHANGE,
                RabbitMqConstants.ORDER_CANCEL_ROUTING_KEY,
                order,
                message -> {
                    message.getMessageProperties().setDelayLong((long) delayMs);
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                },
                correlationData
        );

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("延迟取消消息被 RabbitMQ 拒绝，orderId=" + order.getId()
                        + "，reason=" + confirm.getReason());
            }
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new AmqpException("延迟取消消息无法路由，orderId=" + order.getId()
                        + "，reply=" + returned.getReplyText());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("等待延迟取消消息确认时线程被中断，orderId=" + order.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AmqpException("等待延迟取消消息确认失败，orderId=" + order.getId(), e);
        }
    }
}
