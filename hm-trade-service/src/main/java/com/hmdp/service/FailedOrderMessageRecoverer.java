package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RabbitMqConstants;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

/**
 * 消费重试耗尽后回补 Redis 资格，并将原消息转存失败队列供人工核查。
 */
@Slf4j
@Component
public class FailedOrderMessageRecoverer implements MessageRecoverer {

    private final ObjectMapper objectMapper;
    private final SeckillReservationService reservationService;
    private final VoucherOrderMapper voucherOrderMapper;
    private final RabbitTemplate rabbitTemplate;

    public FailedOrderMessageRecoverer(ObjectMapper objectMapper,
                                       SeckillReservationService reservationService,
                                       VoucherOrderMapper voucherOrderMapper,
                                       RabbitTemplate rabbitTemplate) {
        this.objectMapper = objectMapper;
        this.reservationService = reservationService;
        this.voucherOrderMapper = voucherOrderMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        VoucherOrder order = null;
        try {
            order = objectMapper.readValue(message.getBody(), VoucherOrder.class);
        } catch (Exception parseException) {
            // 消息无法反序列化时不能安全推断库存归属，只保留原文等待人工处理。
            log.error("秒杀订单失败消息无法解析，已直接转入失败队列", parseException);
        }
        if (order != null) {
            if (voucherOrderMapper.selectById(order.getId()) == null) {
                reservationService.rollback(order.getVoucherId(), order.getUserId(), order.getId());
                log.error("秒杀订单消费重试耗尽，已回补资格并转入失败队列，orderId={}", order.getId(), cause);
            } else {
                // 订单已落库时保留 pending，由定时任务继续补发延迟取消消息。
                log.error("秒杀订单后续处理失败，保留 pending 并转入失败队列，orderId={}", order.getId(), cause);
            }
        }
        message.getMessageProperties().setHeader("x-hmdp-failure", cause.getClass().getSimpleName());
        rabbitTemplate.send(
                RabbitMqConstants.ORDER_FAILED_EXCHANGE,
                RabbitMqConstants.ORDER_FAILED_ROUTING_KEY,
                message
        );
    }
}
