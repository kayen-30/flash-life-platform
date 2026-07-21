package com.hmdp.config;

/**
 * 秒杀订单 RabbitMQ 拓扑名称，生产者、消费者和失败恢复共用同一约定。
 */
public final class RabbitMqConstants {

    public static final String ORDER_EXCHANGE = "trade.order.exchange";
    public static final String ORDER_QUEUE = "trade.order.queue";
    public static final String ORDER_ROUTING_KEY = "trade.order.created";
    public static final String ORDER_FAILED_EXCHANGE = "trade.order.failed.exchange";
    public static final String ORDER_FAILED_QUEUE = "trade.order.failed.queue";
    public static final String ORDER_FAILED_ROUTING_KEY = "trade.order.failed";
    public static final String ORDER_LISTENER_FACTORY = "orderRabbitListenerContainerFactory";
    public static final String ORDER_PUBLISH_EXECUTOR = "orderPublishExecutor";

    private RabbitMqConstants() {
    }
}
