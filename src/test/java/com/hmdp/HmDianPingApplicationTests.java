package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "HMDP_RUN_DATA_LOAD_TESTS", matches = "true")
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将店铺坐标按类型导入 Redis GEO，便于按类型做附近店铺查询。
     */
    @Test
    void loadShopData() {
        // GEO key 以店铺类型拆分，查询附近店铺时可以先定位到同类型集合。
        Map<Long, List<Shop>> shopsByType = shopService.list().stream()
                .filter(shop -> shop.getX() != null && shop.getY() != null)
                .collect(Collectors.groupingBy(Shop::getTypeId));

        shopsByType.forEach((typeId, shops) -> {
            String key = SHOP_GEO_KEY + typeId;

            // 重复导入前先清理同类型旧坐标，避免数据库坐标变更后 Redis 仍保留旧位置。
            stringRedisTemplate.delete(key);
            shops.forEach(shop -> stringRedisTemplate.opsForGeo()
                    .add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString()));
        });
    }
}
