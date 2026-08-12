-- 商铺简介用于 Elasticsearch 全文检索；脚本可在已有数据库上重复执行。
SET @shop_description_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_shop'
      AND COLUMN_NAME = 'description'
);
SET @shop_description_sql = IF(
    @shop_description_exists = 0,
    'ALTER TABLE tb_shop ADD COLUMN description varchar(512) NULL COMMENT ''商铺简介'' AFTER name',
    'SELECT 1'
);
PREPARE shop_description_statement FROM @shop_description_sql;
EXECUTE shop_description_statement;
DEALLOCATE PREPARE shop_description_statement;
