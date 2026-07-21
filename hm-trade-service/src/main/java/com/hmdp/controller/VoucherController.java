package com.hmdp.controller;


import com.hmdp.auth.RequireAdmin;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

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
@RequestMapping("/voucher")
@Tag(name = "优惠券接口", description = "普通优惠券、秒杀券和店铺券列表接口")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    @RequireAdmin
    @Operation(summary = "新增普通优惠券", description = "创建普通优惠券并返回新生成的优惠券 id。")
    public Result addVoucher(@RequestBody Voucher voucher) {
        voucherService.save(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    @RequireAdmin
    @Operation(summary = "新增秒杀优惠券", description = "创建优惠券并写入秒杀库存、开始时间和结束时间。")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        voucherService.addSeckillVoucher(voucher);
        return Result.ok(voucher.getId());
    }

    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    @Operation(summary = "查询店铺优惠券", description = "根据店铺 id 查询该店铺下的普通券和秒杀券。")
    public Result queryVoucherOfShop(@Parameter(description = "店铺 id", example = "1") @PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
