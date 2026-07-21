package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;

import java.util.List;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    /**
     * 内部服务读取优惠券摘要时复用同一条关联查询，避免暴露 Mapper。
     */
    List<Voucher> queryVoucherListOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}
