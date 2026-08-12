package com.hmdp.service;

import com.hmdp.config.RabbitMqConstants;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 消费超时取消消息：订单仍处于未支付状态时将其取消，并回补数据库和 Redis 库存。
 */
@Slf4j
@Component
public class OrderCancelConsumer {

    private static final int STATUS_UNPAID    = 1;
    private static final int STATUS_CANCELLED = 4;

    private final IVoucherOrderService voucherOrderService;
    private final ISeckillVoucherService seckillVoucherService;
    private final SeckillReservationService reservationService;

    public OrderCancelConsumer(IVoucherOrderService voucherOrderService,
                               ISeckillVoucherService seckillVoucherService,
                               SeckillReservationService reservationService) {
        this.voucherOrderService = voucherOrderService;
        this.seckillVoucherService = seckillVoucherService;
        this.reservationService  = reservationService;
    }

    @RabbitListener(queues = RabbitMqConstants.ORDER_CANCEL_QUEUE)
    @Transactional
    public void cancelTimedOutOrder(VoucherOrder order) {
        VoucherOrder current = voucherOrderService.getById(order.getId());
        if (current == null) {
            return;
        }
        if (current.getStatus() == STATUS_CANCELLED) {
            replenishRedisAfterCancellation(current);
            return;
        }
        if (current.getStatus() != STATUS_UNPAID) {
            // 已支付或已核销，不执行取消
            return;
        }

        // CAS 更新：只有仍为未支付时才取消，防止与并发支付产生竞态
        boolean updated = voucherOrderService.lambdaUpdate()
                .eq(VoucherOrder::getId,     order.getId())
                .eq(VoucherOrder::getStatus, STATUS_UNPAID)
                .set(VoucherOrder::getStatus, STATUS_CANCELLED)
                .update();

        if (updated) {
            boolean stockReplenished = seckillVoucherService.lambdaUpdate()
                    .setSql("stock = stock + 1")
                    .eq(SeckillVoucher::getVoucherId, current.getVoucherId())
                    .update();
            if (!stockReplenished) {
                throw new IllegalStateException("回补数据库库存失败，orderId=" + current.getId());
            }

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    replenishRedisAfterCancellation(current);
                }
            });
        }
        // updated=false：并发支付刚完成，状态已变，正常忽略
    }

    private void replenishRedisAfterCancellation(VoucherOrder order) {
        boolean rolledBack = reservationService.cancel(
                order.getVoucherId(),
                order.getUserId(),
                order.getId()
        );
        log.info("秒杀订单超时取消，orderId={}，Redis 回补={}", order.getId(), rolledBack);
    }
}
