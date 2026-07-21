package com.hmdp.controller;

import com.hmdp.api.dto.ShopSummaryDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 等内部消费者只读取店铺展示字段，避免共享店铺实体。
 */
@RestController
@RequestMapping("/internal/shops")
public class InternalShopController {

    @Resource
    private IShopService shopService;

    @GetMapping("/{id}")
    public ShopSummaryDTO queryById(@PathVariable("id") Long id) {
        return toSummary(shopService.getById(id));
    }

    @GetMapping("/search")
    public List<ShopSummaryDTO> queryByName(@RequestParam("name") String name) {
        return shopService.query()
                .like("name", name)
                .last("LIMIT 3")
                .list()
                .stream()
                .map(this::toSummary)
                .toList();
    }

    private ShopSummaryDTO toSummary(Shop shop) {
        if (shop == null) {
            return null;
        }
        ShopSummaryDTO dto = new ShopSummaryDTO();
        dto.setId(shop.getId());
        dto.setName(shop.getName());
        dto.setArea(shop.getArea());
        dto.setAddress(shop.getAddress());
        dto.setAvgPrice(shop.getAvgPrice());
        dto.setScore(shop.getScore());
        dto.setSold(shop.getSold());
        dto.setComments(shop.getComments());
        dto.setOpenHours(shop.getOpenHours());
        return dto;
    }
}
