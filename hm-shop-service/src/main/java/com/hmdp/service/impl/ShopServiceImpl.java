package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.AiCacheKeys;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * 店铺服务实现类，查询用缓存，修改后删缓存。
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据店铺id查询详情，逻辑过期时先返回旧值，再异步重建热点数据。
     */
    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY,
                "lock:shop:",
                id,
                Shop.class,
                this::getById,
                30L,
                TimeUnit.MINUTES
        );
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 按类型查询店铺，带坐标时使用 Redis GEO 按距离分页。
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            // 没有坐标时保持原有按类型分页查询，兼容普通列表页。
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;

        // Redis GEO 按距离返回前 end 条，再在内存中跳过前一页数据。
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sort(Sort.Direction.ASC)
                        .limit(end)
        );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pageResults = list.stream()
                .skip(from)
                .limit(SystemConstants.DEFAULT_PAGE_SIZE)
                .toList();

        List<Long> ids = new ArrayList<>(pageResults.size());
        Map<String, Distance> distanceMap = new HashMap<>(pageResults.size());
        pageResults.forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        });

        String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        // 数据库查询按 Redis 返回的距离顺序排序，避免 IN 查询打乱附近店铺顺序。
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        shops.forEach(shop -> {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                // Redis 按公里返回距离，实体里保留米值，方便前端直接展示。
                shop.setDistance(distance.getValue() * 1000);
            }
        });
        return Result.ok(shops);
    }

    /**
     * 更新店铺信息，数据库写入成功后删除缓存，避免后续查询读到旧数据。
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            // 修改店铺必须带id，否则无法定位数据库记录和缓存key。
            return Result.fail("店铺id不能为空！");
        }

        // 只有数据库实际更新成功才删除缓存，避免不存在的店铺也返回成功。
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺不存在或更新失败！");
        }

        // 删除旧缓存，采用旁路缓存策略保证数据最终一致。
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 店铺资料是 AI 推荐的输入，更新后必须同步淘汰生成结果。
        stringRedisTemplate.delete(AiCacheKeys.shopRecommendKey(id));
        return Result.ok();
    }
}
