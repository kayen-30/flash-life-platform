-- 先检查历史重复订单；存在结果时应先确认保留规则，再执行下面的唯一索引语句。
SELECT `user_id`, `voucher_id`, COUNT(*) AS `order_count`
FROM `tb_voucher_order`
GROUP BY `user_id`, `voucher_id`
HAVING COUNT(*) > 1;

-- 为现有数据库执行一次，最终由数据库保证同一用户不能重复购买同一张券。
ALTER TABLE `tb_voucher_order`
    ADD UNIQUE INDEX `uk_voucher_order_user_voucher` (`user_id`, `voucher_id`) USING BTREE;
