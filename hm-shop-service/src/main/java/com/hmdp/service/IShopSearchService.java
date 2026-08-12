package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 商铺 Elasticsearch 检索与索引维护接口。
 */
public interface IShopSearchService {

    Result search(String keyword, Long typeId, Integer current, Integer size);

    void syncShop(Long shopId);

    long initializeIfAbsent();

    long rebuild();
}
