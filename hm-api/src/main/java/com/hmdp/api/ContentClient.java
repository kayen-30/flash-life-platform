package com.hmdp.api;

import com.hmdp.api.dto.BlogSummaryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "hm-content-service", configuration = InternalFeignConfiguration.class)
public interface ContentClient {

    @GetMapping("/internal/blogs/hot")
    List<BlogSummaryDTO> queryHotBlogs(@RequestParam("limit") Integer limit);

    @GetMapping("/internal/blogs/by-shop")
    List<BlogSummaryDTO> queryShopBlogs(@RequestParam("shopId") Long shopId,
                                        @RequestParam("limit") Integer limit);
}
