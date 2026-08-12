-- Add the shop description used by Elasticsearch full-text search.
-- This migration is safe for fresh databases, existing volumes, and reruns.
SET @shop_description_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'tb_shop'
      AND COLUMN_NAME = 'description'
);
SET @shop_description_sql = IF(
    @shop_description_exists = 0,
    'ALTER TABLE tb_shop ADD COLUMN description varchar(512) NULL COMMENT ''Shop description'' AFTER name',
    'SELECT 1'
);
PREPARE shop_description_statement FROM @shop_description_sql;
EXECUTE shop_description_statement;
DEALLOCATE PREPARE shop_description_statement;
