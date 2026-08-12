package com.hmdp.mq;

/**
 * 同步消息只携带主键，消费端以 MySQL 最新记录为准。
 */
public record ShopSyncMessage(Long shopId) {
}
