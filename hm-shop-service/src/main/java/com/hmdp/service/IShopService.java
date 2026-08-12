package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 新增商铺，并在数据库事务提交后触发搜索索引同步。
     */
    Result create(Shop shop);

    Result queryById(Long id);

    Result update(Shop shop);

    /**
     * 按店铺类型查询列表，传入坐标时优先返回附近店铺。
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
