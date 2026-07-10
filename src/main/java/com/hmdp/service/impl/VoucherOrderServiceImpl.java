package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SECKILL_VOUCHER_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SECKILL_VOUCHER_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SECKILL_VOUCHER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀券订单服务实现类。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final int ORDER_QUEUE_CAPACITY = 1024 * 1024;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(ORDER_QUEUE_CAPACITY);
    private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor();

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

    @PostConstruct
    private void initOrderHandler() {
        // 单线程顺序消费已通过 Lua 校验的订单，降低数据库在秒杀瞬间的并发压力。
        seckillOrderExecutor.submit(new VoucherOrderHandler());
    }

    /**
     * 应用关闭时中断订单消费线程，避免线程池跨 Spring 上下文残留。
     */
    @PreDestroy
    private void shutdownOrderHandler() {
        seckillOrderExecutor.shutdownNow();
    }

    /**
     * 秒杀下单：Lua 在 Redis 中原子完成库存预扣和一人一单校验，成功后异步创建订单。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        // 活动元数据走 Redis 缓存，冷缓存由互斥锁保证只回源数据库一次。
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
        // 活动未开始或已结束时不允许下单，避免无效订单占用库存。
        if (voucher.getBeginTime().isAfter(now)) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(now)) {
            return Result.fail("秒杀已经结束");
        }
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                List.of(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                user.getId().toString()
        );
        int code = result == null ? 1 : result.intValue();
        if (code == 1) {
            return Result.fail("库存不足");
        }
        if (code == 2) {
            return Result.fail("用户已经购买过一次");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(user.getId());
        voucherOrder.setVoucherId(voucherId);

        // Redis 已完成资格占位，入队失败时需要回滚 Redis 预扣结果，避免吞单占库存。
        if (!orderTasks.offer(voucherOrder)) {
            rollbackRedisSeckill(voucherId, user.getId());
            return Result.fail("下单人数过多，请稍后再试");
        }
        return Result.ok(orderId);
    }

    /**
     * 缓存首次缺失时加载活动信息，并为旧数据补齐 Redis 库存。
     */
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
     * 创建秒杀订单：事务提交前不释放用户锁，确保重复下单查询能看到上一单。
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 一个用户对同一张秒杀券只能下一单，防止重复抢购。
        long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次");
        }

        // 库存扣减必须带 stock > 0 条件，让数据库在并发场景中保证不会扣成负数。
        boolean success = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }

        // 库存扣减成功后创建未支付订单，订单id使用Redis全局递增ID。
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        if (!save(voucherOrder)) {
            // 保存失败必须抛出异常触发事务回滚，不能只扣库存不生成订单。
            throw new IllegalStateException("保存秒杀订单失败");
        }

        return Result.ok(orderId);
    }

    /**
     * 异步订单处理器，把 Redis 中已抢到资格的请求最终落到数据库。
     */
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                VoucherOrder voucherOrder = null;
                try {
                    voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("秒杀订单处理线程被中断");
                    break;
                } catch (Exception e) {
                    // 后台线程不能因单条订单异常退出，否则后续已抢到资格的订单会一直堆积。
                    if (voucherOrder != null) {
                        rollbackRedisSeckill(voucherOrder.getVoucherId(), voucherOrder.getUserId());
                    }
                    log.error("处理秒杀订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        transactionTemplate.executeWithoutResult(status -> {
            Long voucherId = voucherOrder.getVoucherId();
            Long userId = voucherOrder.getUserId();

            // DB 层保留一人一单校验，防止 Redis 数据被清空或手工修改后产生重复订单。
            long count = query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count();
            if (count > 0) {
                log.warn("用户重复下单，userId={}, voucherId={}", userId, voucherId);
                rollbackRedisSeckill(voucherId, userId);
                return;
            }

            // Redis 是预扣，数据库仍带 stock > 0 条件做最终一致性保护。
            boolean success = seckillVoucherService.lambdaUpdate()
                    .setSql("stock = stock - 1")
                    .eq(SeckillVoucher::getVoucherId, voucherId)
                    .gt(SeckillVoucher::getStock, 0)
                    .update();
            if (!success) {
                log.warn("数据库库存扣减失败，voucherId={}, orderId={}", voucherId, voucherOrder.getId());
                rollbackRedisSeckill(voucherId, userId);
                return;
            }

            if (!save(voucherOrder)) {
                // 异步落库失败由外层统一回补 Redis，同时事务回滚数据库库存。
                throw new IllegalStateException("保存秒杀订单失败，orderId=" + voucherOrder.getId());
            }
        });
    }

    private void rollbackRedisSeckill(Long voucherId, Long userId) {
        // 只有入队或落库兜底失败时回补 Redis，正常重复请求不能回滚他人已占用的库存。
        stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherId);
        stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY + voucherId, userId.toString());
    }
}
