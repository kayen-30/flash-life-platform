package com.hmdp.controller;

import com.hmdp.api.dto.VoucherSummaryDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.VoucherWeekdayPolicyService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 对内部服务提供优惠券摘要，库存等数据仍由交易服务独占维护。
 */
@RestController
@RequestMapping("/internal/vouchers")
public class InternalVoucherController {

    @Resource
    private IVoucherService voucherService;
    @Resource
    private VoucherWeekdayPolicyService weekdayPolicies;

    /** 只返回当前上架券，并附带交易域判定的查询时状态。 */
    @GetMapping
    public List<VoucherSummaryDTO> queryShopVouchers(@RequestParam("shopId") Long shopId,
            @RequestParam(value = "useWeekday", required = false) Integer useWeekday) {
        return voucherService.queryVoucherListOfShop(shopId).stream()
                .map(voucher -> toSummary(voucher, useWeekday))
                .toList();
    }

    private VoucherSummaryDTO toSummary(Voucher voucher, Integer useWeekday) {
        VoucherSummaryDTO dto = new VoucherSummaryDTO();
        dto.setId(voucher.getId());
        dto.setTitle(voucher.getTitle());
        dto.setSubTitle(voucher.getSubTitle());
        dto.setRules(voucher.getRules());
        dto.setPayValue(voucher.getPayValue());
        dto.setActualValue(voucher.getActualValue());
        dto.setStock(voucher.getStock());
        dto.setType(voucher.getType());
        dto.setStatus(voucher.getStatus());
        dto.setBeginTime(voucher.getBeginTime());
        dto.setEndTime(voucher.getEndTime());
        dto.setAvailability(availability(voucher, LocalDateTime.now()));
        dto.setWeekdayCheck(weekdayPolicies.evaluate(voucher, useWeekday));
        return dto;
    }
    /** 查询状态不是库存预占；真正购买仍由下单链路校验。 */
    static String availability(Voucher voucher, LocalDateTime now) {
        if (voucher.getStatus() == null) return "UNKNOWN";
        if (voucher.getStatus() != 1) return "OFF_SHELF";
        if (voucher.getBeginTime() != null && voucher.getEndTime() != null
                && voucher.getBeginTime().isAfter(voucher.getEndTime())) return "UNKNOWN";
        if (voucher.getEndTime() != null && voucher.getEndTime().isBefore(now)) return "EXPIRED";
        if (voucher.getBeginTime() != null && voucher.getBeginTime().isAfter(now)) return "NOT_STARTED";
        if (voucher.getStock() != null && voucher.getStock() == 0) return "SOLD_OUT";
        if (voucher.getStock() == null || voucher.getStock() < 0 || voucher.getType() == null) return "UNKNOWN";
        // 当前只有秒杀券具有完整的库存与活动时段，普通券缺失的数据不能按无限库存解释。
        if (voucher.getType() != 1 || voucher.getBeginTime() == null || voucher.getEndTime() == null) return "UNKNOWN";
        return "IN_STOCK_AT_QUERY";
    }
}
