package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.hmdp.config.RabbitMqConstants;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.SeckillReservationService;
import com.hmdp.service.VoucherOrderPublisher;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SECKILL_VOUCHER_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SECKILL_VOUCHER_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SECKILL_VOUCHER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_INDEX_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀订单服务：Lua 原子预扣资格，RabbitMQ 承接异步订单并提供失败重试。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private VoucherOrderPublisher orderPublisher;

    @Resource
    private SeckillReservationService reservationService;

    /**
     * Lua 原子保存资格和待发布记录；RabbitMQ 暂时不可用时仍由后台任务保证最终投递。
     */
    @Override
    @SentinelResource(value = "seckillVoucher", blockHandler = "handleSeckillBlocked")
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        SeckillVoucher voucher = cacheClient.queryWithLogicalExpire(
                CACHE_SECKILL_VOUCHER_KEY,
                LOCK_SECKILL_VOUCHER_KEY,
                voucherId,
                SeckillVoucher.class,
                this::loadSeckillVoucher,
                CACHE_SECKILL_VOUCHER_TTL,
                TimeUnit.MINUTES
        );
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime().isAfter(now)) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(now)) {
            return Result.fail("秒杀已经结束");
        }

        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                List.of(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_ORDER_KEY + voucherId,
                        SECKILL_PENDING_ORDER_KEY + orderId,
                        SECKILL_PENDING_ORDER_INDEX_KEY
                ),
                user.getId().toString(),
                String.valueOf(orderId),
                voucherId.toString(),
                String.valueOf(System.currentTimeMillis())
        );
        int code = result == null ? 1 : result.intValue();
        if (code == 1) {
            return Result.fail("库存不足");
        }
        if (code == 2) {
            return Result.fail("用户已经购买过一次");
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(user.getId());
        order.setVoucherId(voucherId);
        // 首次 MQ 投递交给独立线程池，HTTP 线程在 Redis 预扣成功后立即返回。
        orderPublisher.publishAsync(order);
        return Result.ok(orderId);
    }

    /**
     * 秒杀流量超过 Sentinel 阈值时快速失败，不继续占用 Redis 和数据库资源。
     */
    public Result handleSeckillBlocked(Long voucherId, BlockException exception) {
        return Result.fail("活动太火爆，请稍后再试");
    }

    private SeckillVoucher loadSeckillVoucher(Long voucherId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher != null) {
            stringRedisTemplate.opsForValue().setIfAbsent(
                    SECKILL_STOCK_KEY + voucherId,
                    String.valueOf(voucher.getStock())
            );
        }
        return voucher;
    }

    /**
     * 保留同步创建入口供普通业务复用，库存与订单仍在交易库内使用本地事务。
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次");
        }
        boolean success = decrementDatabaseStock(voucherId);
        if (!success) {
            return Result.fail("库存不足");
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(redisIdWorker.nextId("order"));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        if (!save(order)) {
            throw new IllegalStateException("保存秒杀订单失败");
        }
        return Result.ok(order.getId());
    }

    /**
     * RabbitMQ 只在本地事务成功后自动 ACK；异常由监听容器重试，耗尽后进入失败恢复器。
     */
    @RabbitListener(
            queues = RabbitMqConstants.ORDER_QUEUE,
            containerFactory = RabbitMqConstants.ORDER_LISTENER_FACTORY
    )
    public void consumeOrder(VoucherOrder order) {
        transactionTemplate.executeWithoutResult(status -> persistOrder(order));
        // 本地事务完成后再清理待发布记录；清理失败会触发消息重试并最终自行修复。
        reservationService.complete(order.getId());
    }

    private void persistOrder(VoucherOrder order) {
        long count = query()
                .eq("user_id", order.getUserId())
                .eq("voucher_id", order.getVoucherId())
                .count();
        if (count > 0) {
            // 重复投递直接视为成功，数据库唯一索引仍是最终幂等防线。
            return;
        }

        if (!decrementDatabaseStock(order.getVoucherId())) {
            reservationService.rollback(order.getVoucherId(), order.getUserId(), order.getId());
            log.warn("数据库库存不足，已回补 Redis，voucherId={}, orderId={}", order.getVoucherId(), order.getId());
            throw new IllegalStateException("数据库库存不足，orderId=" + order.getId());
        }
        if (!save(order)) {
            throw new IllegalStateException("保存秒杀订单失败，orderId=" + order.getId());
        }
    }

    private boolean decrementDatabaseStock(Long voucherId) {
        return seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .update();
    }

}
