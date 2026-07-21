package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 处理优惠券秒杀下单，成功时返回订单id。
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 创建秒杀订单，内部完成一人一单校验和库存扣减。
     */
    Result createVoucherOrder(Long voucherId);
}
