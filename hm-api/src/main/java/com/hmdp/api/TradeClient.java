package com.hmdp.api;

import com.hmdp.api.dto.VoucherSummaryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "hm-trade-service", configuration = InternalFeignConfiguration.class)
public interface TradeClient {

    @GetMapping("/internal/vouchers")
    List<VoucherSummaryDTO> queryShopVouchers(@RequestParam("shopId") Long shopId);
}
