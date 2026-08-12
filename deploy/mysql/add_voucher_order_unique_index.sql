-- 先检查是否存在多个未取消订单；有结果时应先清理异常数据。
SELECT `user_id`, `voucher_id`, COUNT(*) AS `order_count`
FROM `tb_voucher_order`
WHERE `status` <> 4
GROUP BY `user_id`, `voucher_id`
HAVING COUNT(*) > 1;

-- 取消订单允许重新购买；生成列只让未取消订单参与唯一约束。
ALTER TABLE `tb_voucher_order`
    DROP INDEX `uk_voucher_order_user_voucher`,
    ADD COLUMN `active_order` TINYINT(1)
        GENERATED ALWAYS AS (IF(`status` = 4, NULL, 1)) STORED,
    ADD UNIQUE INDEX `uk_voucher_order_active`
        (`user_id`, `voucher_id`, `active_order`) USING BTREE;
