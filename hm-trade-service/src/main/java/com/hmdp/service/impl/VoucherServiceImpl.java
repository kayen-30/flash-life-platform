package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 优惠券服务实现类，负责普通券查询和秒杀券新增。
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 查询指定店铺下的优惠券列表。
     */
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        return Result.ok(queryVoucherListOfShop(shopId));
    }

    @Override
    public List<Voucher> queryVoucherListOfShop(Long shopId) {
        // 基础券和秒杀扩展表由交易服务在本地完成关联，调用方只接收 DTO。
        return getBaseMapper().queryVoucherOfShop(shopId);
    }

    /**
     * 新增秒杀券：基础信息写入tb_voucher，库存和活动时间写入tb_seckill_voucher。
     */
    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 先保存优惠券基础信息，生成优惠券ID后再关联秒杀表。
        save(voucher);

        // 秒杀相关字段不属于tb_voucher，需要单独写入秒杀券表。
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // 库存和购买资格只需保留到活动结束后的补偿窗口，避免历史活动 key 永久堆积。
        long seckillKeyTtlSeconds = Math.max(
                1L,
                Duration.between(
                        LocalDateTime.now(),
                        voucher.getEndTime().plusHours(RedisConstants.SECKILL_KEY_RETENTION_HOURS)
                ).getSeconds()
        );
        stringRedisTemplate.opsForValue()
                .set(
                        RedisConstants.SECKILL_STOCK_KEY + voucher.getId(),
                        String.valueOf(voucher.getStock()),
                        seckillKeyTtlSeconds,
                        TimeUnit.SECONDS
                );

        // 缓存覆盖到活动结束后 30 分钟，活动期间不会因逻辑过期触发重建。
        long cacheTtlSeconds = Math.max(
                1L,
                Duration.between(
                        LocalDateTime.now(),
                        voucher.getEndTime().plusMinutes(RedisConstants.CACHE_SECKILL_VOUCHER_TTL)
                ).getSeconds()
        );
        cacheClient.setWithLogicalExpire(
                RedisConstants.CACHE_SECKILL_VOUCHER_KEY + voucher.getId(),
                seckillVoucher,
                cacheTtlSeconds,
                TimeUnit.SECONDS
        );
    }
}
