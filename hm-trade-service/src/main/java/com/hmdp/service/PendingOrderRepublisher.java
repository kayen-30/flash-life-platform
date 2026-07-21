package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.RedissonLockUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SECKILL_INVALID_PENDING_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_INDEX_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_KEY;

/**
 * 重新发布长期未完成的预扣订单，修复进程在 Redis 预扣后、RabbitMQ 发布前崩溃的窗口。
 */
@Slf4j
@Component
public class PendingOrderRepublisher {

    private static final String REPUBLISH_LOCK = "lock:trade:pending-order-republish";
    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate stringRedisTemplate;
    private final VoucherOrderPublisher orderPublisher;
    private final RedissonLockUtil redissonLockUtil;

    @Value("${hmdp.trade.pending-order-timeout-ms:60000}")
    private long pendingOrderTimeoutMs;

    public PendingOrderRepublisher(StringRedisTemplate stringRedisTemplate,
                                   VoucherOrderPublisher orderPublisher,
                                   RedissonLockUtil redissonLockUtil) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.orderPublisher = orderPublisher;
        this.redissonLockUtil = redissonLockUtil;
    }

    /**
     * 多实例只允许一个任务扫描；重新发布后刷新时间戳，避免短时间内重复投递。
     */
    @Scheduled(fixedDelayString = "${hmdp.trade.pending-order-scan-ms:30000}", initialDelayString = "30000")
    public void republishTimedOutOrders() {
        RLock lock = redissonLockUtil.getLock(REPUBLISH_LOCK);
        boolean locked = false;
        try {
            // 批次耗时受 RabbitMQ confirm 影响，使用看门狗续期避免固定租约过期后多实例并发扫描。
            locked = lock.tryLock();
            if (!locked) {
                return;
            }
            long deadline = System.currentTimeMillis() - pendingOrderTimeoutMs;
            Set<String> orderIds = stringRedisTemplate.opsForZSet().rangeByScore(
                    SECKILL_PENDING_ORDER_INDEX_KEY, 0, deadline, 0, BATCH_SIZE
            );
            if (orderIds == null || orderIds.isEmpty()) {
                return;
            }
            for (String orderId : orderIds) {
                republish(orderId);
            }
        } finally {
            if (locked) {
                redissonLockUtil.unlock(lock);
            }
        }
    }

    private void republish(String orderId) {
        Map<Object, Object> values = stringRedisTemplate.opsForHash()
                .entries(SECKILL_PENDING_ORDER_KEY + orderId);
        if (values.isEmpty()) {
            // 索引孤儿无法恢复业务字段，移除后报警，避免每轮重复扫描。
            stringRedisTemplate.opsForSet().add(SECKILL_INVALID_PENDING_ORDER_KEY, orderId);
            stringRedisTemplate.opsForZSet().remove(SECKILL_PENDING_ORDER_INDEX_KEY, orderId);
            log.error("待发布秒杀订单缺少详情，orderId={}", orderId);
            return;
        }
        try {
            VoucherOrder order = new VoucherOrder();
            order.setId(Long.valueOf(requiredValue(values, "id")));
            order.setUserId(Long.valueOf(requiredValue(values, "userId")));
            order.setVoucherId(Long.valueOf(requiredValue(values, "voucherId")));
            orderPublisher.publish(order);
            stringRedisTemplate.opsForZSet().add(
                    SECKILL_PENDING_ORDER_INDEX_KEY,
                    orderId,
                    System.currentTimeMillis()
            );
            log.warn("重新发布超时秒杀订单，orderId={}", orderId);
        } catch (IllegalArgumentException e) {
            // 保留原始 Hash 并隔离索引，既避免无限重试，也便于人工核对损坏数据。
            stringRedisTemplate.opsForSet().add(SECKILL_INVALID_PENDING_ORDER_KEY, orderId);
            stringRedisTemplate.opsForZSet().remove(SECKILL_PENDING_ORDER_INDEX_KEY, orderId);
            log.error("待发布秒杀订单字段损坏，已转入异常集合，orderId={}", orderId, e);
        } catch (RuntimeException e) {
            // 保留原时间戳，下次扫描继续尝试，数据库唯一索引负责消费幂等。
            log.error("重新发布超时秒杀订单失败，orderId={}", orderId, e);
        }
    }

    private String requiredValue(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("待发布订单缺少字段: " + key);
        }
        return value.toString();
    }
}
