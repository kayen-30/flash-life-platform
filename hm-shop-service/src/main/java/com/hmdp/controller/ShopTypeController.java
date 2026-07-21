package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.CacheClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * 店铺类型接口，首页分类列表适合做固定缓存。
 */
@RestController
@RequestMapping("/shop-type")
@Tag(name = "商铺类型接口", description = "首页商铺分类列表接口")
public class ShopTypeController {

    @Resource
    private IShopTypeService typeService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询店铺类型列表，优先复用Redis中的结果，减少首页高频查询压力。
     */
    @GetMapping("list")
    @Operation(summary = "查询商铺类型列表", description = "优先从 Redis 缓存读取分类列表，缓存未命中时查询数据库。")
    public Result queryTypeList() {
        String typeListJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        if (StrUtil.isNotBlank(typeListJson)) {
            return Result.ok(JSONUtil.toList(typeListJson, ShopType.class));
        }

        List<ShopType> typeList = typeService.query().orderByAsc("sort").list();
        // 店铺类型是稳定字典数据，写入普通缓存即可。
        cacheClient.set(CACHE_SHOP_TYPE_KEY, typeList, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
