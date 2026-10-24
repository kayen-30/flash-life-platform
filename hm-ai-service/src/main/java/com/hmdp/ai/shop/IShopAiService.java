package com.hmdp.ai.shop;

import com.hmdp.dto.Result;


public interface IShopAiService {

    Result generateRecommend(Long shopId);
}
