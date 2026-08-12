package com.hmdp.config;

/**
 * 商铺搜索索引同步使用的 RabbitMQ 拓扑约定。
 */
public final class ShopSearchMqConstants {

    public static final String EXCHANGE = "shop.search.sync.exchange";
    public static final String QUEUE = "shop.search.sync.queue";
    public static final String ROUTING_KEY = "shop.search.sync";
    public static final String DEAD_LETTER_EXCHANGE = "shop.search.sync.dlx";
    public static final String DEAD_LETTER_QUEUE = "shop.search.sync.dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = "shop.search.sync.dead";
    public static final String LISTENER_FACTORY = "shopSearchListenerContainerFactory";

    private ShopSearchMqConstants() {
    }
}
