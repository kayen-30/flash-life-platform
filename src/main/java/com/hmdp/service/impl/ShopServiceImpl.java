package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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

        // 先更新数据库，确保删除缓存后下一次查询能回源拿到新数据。
        updateById(shop);

        // 删除旧缓存，采用旁路缓存策略保证数据最终一致。
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
