package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IShopAiService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 保留原店铺推荐 URL，由网关优先路由到独立 AI 服务，前端无需跟随拆分修改。
 */
@RestController
@RequestMapping("/shop")
public class ShopAiController {

    @Resource
    private IShopAiService shopAiService;

    @GetMapping("/{id}/ai-recommend")
    public Result generateRecommend(@PathVariable("id") Long id) {
        return shopAiService.generateRecommend(id);
    }
}
