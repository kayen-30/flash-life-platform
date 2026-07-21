package com.hmdp.api;

import com.hmdp.api.dto.ShopSummaryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "hm-shop-service", configuration = InternalFeignConfiguration.class)
public interface ShopClient {

    @GetMapping("/internal/shops/{id}")
    ShopSummaryDTO queryById(@PathVariable("id") Long id);

    @GetMapping("/internal/shops/search")
    List<ShopSummaryDTO> queryByName(@RequestParam("name") String name);
}
