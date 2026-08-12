-- 先检查是否存在多个未取消订单；有结果时应先清理异常数据。
SELECT `user_id`, `voucher_id`, COUNT(*) AS `order_count`
FROM `tb_voucher_order`
WHERE `status` <> 4
GROUP BY `user_id`, `voucher_id`
HAVING COUNT(*) > 1;

-- 兼容旧数据卷、全新初始化和重复执行。全新库的 hmdp.sql 已包含新列和新索引，
-- 因此不能直接再次 DROP/ADD；旧数据卷则按实际结构逐步升级。
SET @drop_legacy_index_sql = IF(
    EXISTS (
        SELECT 1
        FROM `information_schema`.`statistics`
        WHERE `table_schema` = DATABASE()
          AND `table_name` = 'tb_voucher_order'
          AND `index_name` = 'uk_voucher_order_user_voucher'
    ),
    'ALTER TABLE `tb_voucher_order` DROP INDEX `uk_voucher_order_user_voucher`',
    'DO 0'
);
PREPARE `voucher_order_migration_stmt` FROM @drop_legacy_index_sql;
EXECUTE `voucher_order_migration_stmt`;
DEALLOCATE PREPARE `voucher_order_migration_stmt`;

SET @add_active_order_sql = IF(
    EXISTS (
        SELECT 1
        FROM `information_schema`.`columns`
        WHERE `table_schema` = DATABASE()
          AND `table_name` = 'tb_voucher_order'
          AND `column_name` = 'active_order'
    ),
    'DO 0',
    'ALTER TABLE `tb_voucher_order` ADD COLUMN `active_order` TINYINT(1) GENERATED ALWAYS AS (IF(`status` = 4, NULL, 1)) STORED'
);
PREPARE `voucher_order_migration_stmt` FROM @add_active_order_sql;
EXECUTE `voucher_order_migration_stmt`;
DEALLOCATE PREPARE `voucher_order_migration_stmt`;

-- 取消订单生成 NULL，可以保留多条；未取消订单生成 1，仍保持一人一单。
SET @add_active_index_sql = IF(
    EXISTS (
        SELECT 1
        FROM `information_schema`.`statistics`
        WHERE `table_schema` = DATABASE()
          AND `table_name` = 'tb_voucher_order'
          AND `index_name` = 'uk_voucher_order_active'
    ),
    'DO 0',
    'ALTER TABLE `tb_voucher_order` ADD UNIQUE INDEX `uk_voucher_order_active` (`user_id`, `voucher_id`, `active_order`) USING BTREE'
);
PREPARE `voucher_order_migration_stmt` FROM @add_active_index_sql;
EXECUTE `voucher_order_migration_stmt`;
DEALLOCATE PREPARE `voucher_order_migration_stmt`;
