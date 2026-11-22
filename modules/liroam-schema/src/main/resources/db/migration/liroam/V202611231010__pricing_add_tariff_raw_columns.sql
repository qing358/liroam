-- 资费表原样留存补两列（domain-design §8）：
-- 注意事项全文在激活时重新导出可用区域的实名标记与限制说明，草稿期间不动前台；
-- 卡资费行的「卡类型」（eSIM / 实体卡 …）没有对应列，原文单独存。

ALTER TABLE lr_tariff_sheet
    ADD COLUMN notes_raw TEXT NULL COMMENT '资费表「注意事项」原文' AFTER imported_by;

ALTER TABLE lr_tariff_line
    ADD COLUMN item_raw VARCHAR(64) NULL COMMENT 'CARD_FEE 行的卡类型原文' AFTER direction_raw;
