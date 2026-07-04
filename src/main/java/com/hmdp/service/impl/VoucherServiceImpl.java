package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;

/**
 * 优惠券服务实现类，负责普通券查询和秒杀券新增。
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    /**
     * 查询指定店铺下的优惠券列表。
     */
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券基础信息和秒杀券扩展信息。
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
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
    }
}
