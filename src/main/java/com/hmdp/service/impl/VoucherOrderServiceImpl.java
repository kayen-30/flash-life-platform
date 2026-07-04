package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedissonLockUtil;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 秒杀券订单服务实现类。
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonLockUtil redissonLockUtil;

    /**
     * 秒杀下单：先校验活动状态，再按用户获取Redis分布式锁，保证一人一单。
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
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
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        String lockKey = "lock:order:" + user.getId();
        // Redisson锁按用户维度串行化下单请求，并由看门狗处理业务执行期间的自动续期。
        boolean isLock = redissonLockUtil.tryLock(lockKey);
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            // 分布式锁按用户维度串行化请求，避免不同JVM实例同时通过一人一单校验。
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 只释放当前线程持有的锁，避免业务超时或重入场景下误解锁。
            redissonLockUtil.unlock(lockKey);
        }
    }

    /**
     * 创建秒杀订单：事务提交前不释放用户锁，确保重复下单查询能看到上一单。
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 一个用户对同一张秒杀券只能下一单，防止重复抢购。
        int count = query()
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
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
