package com.hmdp.config;

import com.hmdp.service.IShopSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Docker 本地环境首次启动时创建索引并导入存量商铺。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hmdp.elasticsearch", name = "initialize-on-startup", havingValue = "true")
public class ShopSearchIndexInitializer implements ApplicationRunner {

    private final IShopSearchService shopSearchService;

    public ShopSearchIndexInitializer(IShopSearchService shopSearchService) {
        this.shopSearchService = shopSearchService;
    }

    @Override
    public void run(ApplicationArguments args) {
        long count = shopSearchService.initializeIfAbsent();
        if (count > 0) {
            log.info("商铺 Elasticsearch 索引初始化完成，导入数量={}", count);
        }
    }
}
