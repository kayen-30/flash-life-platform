package com.hmdp.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_LEGACY_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_INDEX_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 负责幂等回滚 Redis 中的秒杀库存和购买资格。
 */
@Service
public class SeckillReservationService {

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT;
    private static final DefaultRedisScript<Long> CANCEL_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("lua/rollback_seckill.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
        COMPLETE_SCRIPT = new DefaultRedisScript<>();
        COMPLETE_SCRIPT.setLocation(new ClassPathResource("lua/complete_seckill.lua"));
        COMPLETE_SCRIPT.setResultType(Long.class);
        CANCEL_SCRIPT = new DefaultRedisScript<>();
        CANCEL_SCRIPT.setLocation(new ClassPathResource("lua/cancel_seckill.lua"));
        CANCEL_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public SeckillReservationService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 只有购买资格仍存在时才回补一次，重复调用不会造成库存膨胀。
     */
    public boolean rollback(Long voucherId, Long userId, Long orderId) {
        Long result = stringRedisTemplate.execute(
                ROLLBACK_SCRIPT,
                List.of(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_ORDER_KEY + voucherId,
                        SECKILL_PENDING_ORDER_KEY + orderId,
                        SECKILL_PENDING_ORDER_INDEX_KEY,
                        SECKILL_LEGACY_ORDER_KEY + voucherId
                ),
                userId.toString(),
                orderId.toString()
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 数据库订单已经落库后删除待发布标记，重复执行保持幂等。
     */
    public void complete(Long orderId) {
        stringRedisTemplate.execute(
                COMPLETE_SCRIPT,
                List.of(SECKILL_PENDING_ORDER_KEY + orderId, SECKILL_PENDING_ORDER_INDEX_KEY),
                orderId.toString()
        );
    }

    /**
     * 超时取消专用：只回补仍属于当前订单的库存和购买资格。
     * 库存 key 已过期（活动结束超过48小时）时跳过回补，不膨胀库存。
     */
    public boolean cancel(Long voucherId, Long userId, Long orderId) {
        Long result = stringRedisTemplate.execute(
                CANCEL_SCRIPT,
                List.of(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_ORDER_KEY + voucherId,
                        SECKILL_LEGACY_ORDER_KEY + voucherId
                ),
                userId.toString(),
                orderId.toString()
        );
        return Long.valueOf(1L).equals(result);
    }
}
