package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import com.hmdp.auth.RequireAdmin;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop")
@Tag(name = "商铺接口", description = "商铺详情、商铺分页查询、商铺维护和 AI 推荐接口")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询商铺详情", description = "根据商铺 id 查询详情，业务层会处理缓存读取和缓存重建。")
    public Result queryShopById(@Parameter(description = "商铺 id", example = "1") @PathVariable("id") Long id) {
        return shopService.queryById(id);
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    @RequireAdmin
    @Operation(summary = "新增商铺", description = "保存商铺信息并返回新生成的商铺 id。")
    public Result saveShop(@RequestBody Shop shop) {
        // 写入数据库
        shopService.save(shop);
        // 返回店铺id
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    @RequireAdmin
    @Operation(summary = "更新商铺", description = "更新商铺信息，并由业务层处理缓存失效。")
    public Result updateShop(@RequestBody Shop shop) {
        return shopService.update(shop);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @param x 用户经度
     * @param y 用户纬度
     * @return 商铺列表
     */
    @GetMapping("/of/type")
    @Operation(summary = "按类型分页查询商铺", description = "传入经纬度时按距离查询附近商铺，否则按类型普通分页查询。")
    public Result queryShopByType(
            @Parameter(description = "商铺类型 id", example = "1") @RequestParam("typeId") Integer typeId,
            @Parameter(description = "页码，从 1 开始", example = "1") @RequestParam(value = "current", defaultValue = "1") Integer current,
            @Parameter(description = "用户经度", example = "121.499744") @RequestParam(value = "x", required = false) Double x,
            @Parameter(description = "用户纬度", example = "31.239637") @RequestParam(value = "y", required = false) Double y
    ) {
        // 传入坐标时查询附近商户，否则保持普通类型分页查询。
        return shopService.queryShopByType(typeId, current, x, y);
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    @Operation(summary = "按名称搜索商铺", description = "根据商铺名称关键字分页查询商铺列表。")
    public Result queryShopByName(
            @Parameter(description = "商铺名称关键字", example = "茶") @RequestParam(value = "name", required = false) String name,
            @Parameter(description = "页码，从 1 开始", example = "1") @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
