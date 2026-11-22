-- 积分兑券活动补充 published 字段，区分未上架草稿与已下架活动（已下架后不可再编辑或重新上架）。
-- 作者 Bulbasaur，创建日期 2026-11-17。
-- 不写死库名、不加 USE，可重复执行；DDL 隐式提交，不能整份事务回滚。

SET @col_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'li_points_coupon'
    AND COLUMN_NAME = 'published'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE li_points_coupon ADD COLUMN published bit(1) NOT NULL DEFAULT b''0'' COMMENT ''0草稿，1已发布；已下架后不可再编辑或重新上架'' AFTER enabled',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 历史已上架或已有兑换记录的活动标记为已发布。
UPDATE li_points_coupon
SET published = b'1'
WHERE published = b'0'
  AND (enabled = b'1' OR exchanged > 0);
