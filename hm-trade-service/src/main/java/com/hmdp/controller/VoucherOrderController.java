package com.hmdp.controller;


import com.hmdp.config.OpenApiConfig;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
@Tag(name = "秒杀订单接口", description = "优惠券秒杀下单接口")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀优惠券下单入口，具体校验和扣库存交给业务层处理。
     */
    @PostMapping("seckill/{id}")
    @Operation(summary = "秒杀优惠券下单", description = "当前登录用户抢购指定秒杀券，库存校验和一人一单由业务层处理。")
    @SecurityRequirement(name = OpenApiConfig.AUTHORIZATION_HEADER)
    public Result seckillVoucher(@Parameter(description = "秒杀券 id", example = "1") @PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
