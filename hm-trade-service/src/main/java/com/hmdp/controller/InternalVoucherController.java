package com.hmdp.controller;

import com.hmdp.api.dto.VoucherSummaryDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.service.IVoucherService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对内部服务提供优惠券摘要，库存等数据仍由交易服务独占维护。
 */
@RestController
@RequestMapping("/internal/vouchers")
public class InternalVoucherController {

    @Resource
    private IVoucherService voucherService;

    @GetMapping
    public List<VoucherSummaryDTO> queryShopVouchers(@RequestParam("shopId") Long shopId) {
        return voucherService.queryVoucherListOfShop(shopId).stream()
                .map(this::toSummary)
                .toList();
    }

    private VoucherSummaryDTO toSummary(Voucher voucher) {
        VoucherSummaryDTO dto = new VoucherSummaryDTO();
        dto.setId(voucher.getId());
        dto.setTitle(voucher.getTitle());
        dto.setSubTitle(voucher.getSubTitle());
        dto.setRules(voucher.getRules());
        dto.setPayValue(voucher.getPayValue());
        dto.setActualValue(voucher.getActualValue());
        dto.setStock(voucher.getStock());
        return dto;
    }
}
