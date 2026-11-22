-- 本机演示目录：只由 `node scripts/dev.mjs demo` 对本机 liroam 库执行，幂等；不得进入任何正式环境迁移。
-- 两家模拟供应商（适配器 mock-shape-a / mock-shape-b，只在 dev/test 装配）、套餐模板、壳商品与旅行卡 SKU 绑定。
-- 卡产品与可用区域来自资费表导入（demo 命令先导入、启用资费表，再执行本文件第二遍补齐绑定）；售价由 demo 命令经后台手工定价写回壳。
-- 固定 id 号段 9100000000000000001–9100000000000009999（壳 li_goods / li_goods_sku），lr_ 表用 code / 唯一键防重。

INSERT IGNORE INTO lr_supplier (code, name, adapter_key, enabled, listing_enabled) VALUES
    ('SUP_A', '演示供应商 A（池卡 · 导入交码）', 'mock-shape-a', 1, 1),
    ('SUP_B', '演示供应商 B（下单即建 · 接口交码）', 'mock-shape-b', 1, 1);

INSERT IGNORE INTO lr_plan_template (id, name, data_quota_bytes, validity_days) VALUES
    (9100001, '7 天 5GB', 5 * 1073741824, 7),
    (9100002, '7 天 3GB', 3 * 1073741824, 7),
    (9100003, '1 天 1GB 加油包', 1073741824, 1);

-- 壳商品：以基线自营店一件已上架商品为模板复制，改成虚拟商品（eSIM 无需物流）。
DROP TEMPORARY TABLE IF EXISTS demo_sku;
CREATE TEMPORARY TABLE demo_sku (
    goods_id     BIGINT       NOT NULL,
    sku_id       BIGINT       NOT NULL,
    goods_name   VARCHAR(128) NOT NULL,
    supplier     VARCHAR(32)  NOT NULL,
    region       VARCHAR(16)  NOT NULL,
    plan_id      BIGINT       NOT NULL,
    package_code VARCHAR(64)  NOT NULL,
    sku_kind     VARCHAR(16)  NOT NULL,
    price        DECIMAL(10, 2) NOT NULL
);
INSERT INTO demo_sku VALUES
    (9100000000000000001, 9100000000000001001, '日本 eSIM 7 天 5GB（演示 A）', 'SUP_A', 'JP', 9100001, '5GB-7D', 'NEW_CARD', 39.90),
    (9100000000000000002, 9100000000000001002, '中国内地 eSIM 7 天 5GB（演示 A）', 'SUP_A', 'CN', 9100001, '5GB-7D', 'NEW_CARD', 29.90),
    (9100000000000000003, 9100000000000001003, '日本 eSIM 7 天 3GB（演示 B）', 'SUP_B', 'JP', 9100002, '3GB-7D', 'NEW_CARD', 25.90),
    (9100000000000000004, 9100000000000001004, '日本 1 天 1GB 加油包（演示 A）', 'SUP_A', 'JP', 9100003, '1GB-1D', 'TOP_UP', 9.90),
    (9100000000000000005, 9100000000000001005, '日本 1 天 1GB 加油包（演示 B）', 'SUP_B', 'JP', 9100003, '1GB-1D', 'TOP_UP', 8.90);

DROP TEMPORARY TABLE IF EXISTS demo_goods_row;
CREATE TEMPORARY TABLE demo_goods_row AS SELECT * FROM li_goods WHERE id = 1377802526690115584;
DROP TEMPORARY TABLE IF EXISTS demo_goods;
CREATE TEMPORARY TABLE demo_goods AS SELECT * FROM li_goods WHERE 1 = 0;
INSERT INTO demo_goods SELECT * FROM demo_goods_row;
INSERT INTO demo_goods SELECT * FROM demo_goods_row;
INSERT INTO demo_goods SELECT * FROM demo_goods_row;
INSERT INTO demo_goods SELECT * FROM demo_goods_row;
INSERT INTO demo_goods SELECT * FROM demo_goods_row;
SET @n = 0;
UPDATE demo_goods SET id = 9100000000000000000 + (@n := @n + 1);
UPDATE demo_goods g JOIN demo_sku d ON d.goods_id = g.id
SET g.goods_name = d.goods_name, g.goods_type = 'VIRTUAL_GOODS', g.price = d.price, g.cost = 0, g.quantity = 999,
    g.template_id = '0', g.weight = 0, g.sn = CONCAT('LRDEMO', d.goods_id % 10000), g.selling_point = '扫码即装，到达即用',
    g.intro = '<p>演示商品：使用区域与限制以页面「使用区域说明」为准。</p>', g.mobile_intro = NULL,
    g.buy_count = 0, g.comment_num = 0, g.create_time = NOW(6), g.update_time = NOW(6);
INSERT IGNORE INTO li_goods SELECT * FROM demo_goods;

DROP TEMPORARY TABLE IF EXISTS demo_goods_sku_row;
CREATE TEMPORARY TABLE demo_goods_sku_row AS SELECT * FROM li_goods_sku WHERE goods_id = '1377802526690115584' LIMIT 1;
DROP TEMPORARY TABLE IF EXISTS demo_goods_sku;
CREATE TEMPORARY TABLE demo_goods_sku AS SELECT * FROM li_goods_sku WHERE 1 = 0;
INSERT INTO demo_goods_sku SELECT * FROM demo_goods_sku_row;
INSERT INTO demo_goods_sku SELECT * FROM demo_goods_sku_row;
INSERT INTO demo_goods_sku SELECT * FROM demo_goods_sku_row;
INSERT INTO demo_goods_sku SELECT * FROM demo_goods_sku_row;
INSERT INTO demo_goods_sku SELECT * FROM demo_goods_sku_row;
SET @n = 0;
UPDATE demo_goods_sku SET id = 9100000000000001000 + (@n := @n + 1);
UPDATE demo_goods_sku s JOIN demo_sku d ON d.sku_id = s.id
SET s.goods_id = d.goods_id, s.goods_name = d.goods_name, s.goods_type = 'VIRTUAL_GOODS', s.price = d.price, s.cost = 0,
    s.quantity = 999, s.freight_template_id = '0', s.weight = 0, s.sn = CONCAT('LRDEMO', d.sku_id % 10000), s.simple_specs = '',
    s.selling_point = '扫码即装，到达即用', s.buy_count = 0, s.comment_num = 0, s.promotion_flag = b'0',
    s.create_time = NOW(6), s.update_time = NOW(6);
INSERT IGNORE INTO li_goods_sku SELECT * FROM demo_goods_sku;

-- 套餐映射与 SKU 绑定：卡产品取各供应商资费表里的资源 45412_C；资费表未导入时这里不插任何行。
INSERT INTO lr_supplier_offering (supplier_id, card_product_id, region_id, plan_template_id, kind, upstream_package_code)
SELECT s.id, p.id, r.id, d.plan_id, IF(d.sku_kind = 'TOP_UP', 'TOP_UP', 'BASE'), d.package_code
FROM demo_sku d
JOIN lr_supplier s ON s.code = d.supplier
JOIN lr_card_product p ON p.supplier_id = s.id AND p.card_type_code = '45412_C'
JOIN lr_usage_region r ON r.code = d.region
WHERE NOT EXISTS (SELECT 1 FROM lr_sku_binding b WHERE b.li_goods_sku_id = CAST(d.sku_id AS CHAR))
  AND NOT EXISTS (SELECT 1 FROM lr_supplier_offering o WHERE o.supplier_id = s.id AND o.card_product_id = p.id AND o.region_id = r.id
                  AND o.plan_template_id = d.plan_id AND o.upstream_package_code = d.package_code);

INSERT IGNORE INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, form, plan_template_id,
                                   offering_id, listing_state)
SELECT CAST(d.sku_id AS CHAR), CAST(d.goods_id AS CHAR), d.sku_kind, s.id, p.id, r.id, 'ESIM', d.plan_id, o.id, 'LISTED'
FROM demo_sku d
JOIN lr_supplier s ON s.code = d.supplier
JOIN lr_card_product p ON p.supplier_id = s.id AND p.card_type_code = '45412_C'
JOIN lr_usage_region r ON r.code = d.region
JOIN lr_supplier_offering o ON o.supplier_id = s.id AND o.card_product_id = p.id AND o.region_id = r.id
                           AND o.plan_template_id = d.plan_id AND o.upstream_package_code = d.package_code;

-- demo 命令据此逐个定价、导池卡（只读）。
SELECT b.id AS binding_id, b.li_goods_sku_id, s.code AS supplier, s.id AS supplier_id, b.card_product_id, d.price
FROM lr_sku_binding b JOIN lr_supplier s ON s.id = b.supplier_id JOIN demo_sku d ON CAST(d.sku_id AS CHAR) = b.li_goods_sku_id
ORDER BY b.li_goods_sku_id;
