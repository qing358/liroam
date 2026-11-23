-- 本机演示目录：只由 `node scripts/dev.mjs demo` 对本机 liroam 库执行，幂等；不得进入任何正式环境迁移。
-- 两家模拟供应商（适配器 mock-shape-a / mock-shape-b，只在 dev/test 装配）、套餐模板、壳商品与旅行卡 SKU 绑定。
-- 覆盖全卡型：纯流量 eSIM、语音短信（卡产品 45407）、实名后交付（香港，资费表标记需实名）、实体卡（实物商品，录物流交付）、
-- 纯软卡（demo 命令经后台「开卖软卡」打开后才会绑定：卡产品 allowed_forms 不含该形态的行这里不插）。
-- 卡产品与可用区域来自资费表导入（demo 命令先导入、启用资费表，再执行本文件第二遍补齐绑定）；售价由 demo 命令经后台手工定价写回壳。
-- 固定 id 号段 9100000000000000001–9100000000000009999（壳 li_goods / li_goods_sku / 演示店铺、店主、分类），lr_ 表用 code / 唯一键防重。

-- lr_ 表为 utf8mb4_general_ci；连接与临时表统一到同一排序规则，避免与服务端默认 0900_ai_ci 混比。
SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

INSERT IGNORE INTO lr_supplier (code, name, adapter_key, enabled, listing_enabled) VALUES
    ('SUP_A', '演示供应商 A（池卡 · 导入交码）', 'mock-shape-a', 1, 1),
    ('SUP_B', '演示供应商 B（下单即建 · 接口交码）', 'mock-shape-b', 1, 1);

INSERT IGNORE INTO lr_plan_template (id, name, data_quota_bytes, validity_days) VALUES
    (9100001, '7 天 5GB', 5 * 1073741824, 7),
    (9100002, '7 天 3GB', 3 * 1073741824, 7),
    (9100003, '1 天 1GB 加油包', 1073741824, 1);
INSERT IGNORE INTO lr_plan_template (id, name, data_quota_bytes, voice_seconds, sms_count, validity_days) VALUES
    (9100004, '7 天 3GB + 100 分钟 + 50 条', 3 * 1073741824, 6000, 50, 7);

-- 壳商品：eSIM 为虚拟商品（无需物流），实体卡为实物商品（运费模板 0 = 包邮）。
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
    price        DECIMAL(10, 2) NOT NULL,
    card_type    VARCHAR(32)  NOT NULL,
    form         VARCHAR(16)  NOT NULL,
    kyc_gate     VARCHAR(16)  NOT NULL,
    goods_type   VARCHAR(32)  NOT NULL
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
INSERT INTO demo_sku VALUES
    (9100000000000000001, 9100000000000001001, '日本 eSIM 7 天 5GB（演示 A）', 'SUP_A', 'JP', 9100001, '5GB-7D', 'NEW_CARD', 39.90, '45412_C', 'ESIM', 'NONE', 'VIRTUAL_GOODS'),
    (9100000000000000002, 9100000000000001002, '中国内地 eSIM 7 天 5GB（演示 A）', 'SUP_A', 'CN', 9100001, '5GB-7D', 'NEW_CARD', 29.90, '45412_C', 'ESIM', 'NONE', 'VIRTUAL_GOODS'),
    (9100000000000000003, 9100000000000001003, '日本 eSIM 7 天 3GB（演示 B）', 'SUP_B', 'JP', 9100002, '3GB-7D', 'NEW_CARD', 25.90, '45412_C', 'ESIM', 'NONE', 'VIRTUAL_GOODS'),
    (9100000000000000004, 9100000000000001004, '日本 1 天 1GB 加油包（演示 A）', 'SUP_A', 'JP', 9100003, '1GB-1D', 'TOP_UP', 9.90, '45412_C', 'ESIM', 'NONE', 'VIRTUAL_GOODS'),
    (9100000000000000005, 9100000000000001005, '日本 1 天 1GB 加油包（演示 B）', 'SUP_B', 'JP', 9100003, '1GB-1D', 'TOP_UP', 8.90, '45412_C', 'ESIM', 'NONE', 'VIRTUAL_GOODS'),
    (9100000000000000006, 9100000000000001006, '日本 eSIM 7 天 3GB + 100 分钟通话 + 50 条短信（演示 B）', 'SUP_B', 'JP', 9100004, '3GB-7D-100MIN-50SMS', 'NEW_CARD', 49.90, '45407', 'ESIM', 'NONE', 'VIRTUAL_GOODS'),
    (9100000000000000007, 9100000000000001007, '香港 eSIM 7 天 3GB（演示 B · 需实名）', 'SUP_B', 'HK', 9100002, '3GB-7D', 'NEW_CARD', 29.90, '45412_C', 'ESIM', 'BLOCK_DELIVERY', 'VIRTUAL_GOODS'),
    (9100000000000000008, 9100000000000001008, '日本 实体 SIM 卡 7 天 5GB（演示 B · 寄送）', 'SUP_B', 'JP', 9100001, '5GB-7D', 'NEW_CARD', 45.90, '45412_C', 'PHYSICAL', 'NONE', 'PHYSICAL_GOODS'),
    (9100000000000000009, 9100000000000001009, '日本 纯软卡 7 天 3GB（演示 B · 写入软卡设备）', 'SUP_B', 'JP', 9100002, '3GB-7D', 'NEW_CARD', 19.90, '45412_C', 'SOFT', 'NONE', 'VIRTUAL_GOODS');

-- 演示自营店与店主（商家端登录 liroam_demo_seller / 111111；口令哈希即上游演示账号同口令的哈希）、三级演示分类。
-- 基线 SQL 不带任何店铺、会员、分类、商品，这里自建，不依赖上游样例数据。
INSERT IGNORE INTO li_member (id, create_by, create_time, delete_flag, disabled, have_store, mobile, nick_name, password, point, sex,
                              store_id, username, client_enum, total_point)
VALUES (9100000000000002001, 'liroam-demo', NOW(6), b'0', b'1', b'1', '13900000009', '演示店主',
        '$2a$10$1EUGPWBsN4dPr3MDTs/M0OEWLjmENuCt5vyB8M7VYqrqNeBwxC78K', 0, 0, '9100000000000002002', 'liroam_demo_seller', 'PC', 0);
INSERT IGNORE INTO li_store (id, create_by, create_time, delete_flag, member_id, member_name, self_operated, store_disable, store_name,
                             store_desc, delivery_score, description_score, service_score, goods_num, collection_num, page_show, self_pick_flag)
VALUES (9100000000000002002, 'liroam-demo', NOW(6), b'0', '9100000000000002001', 'liroam_demo_seller', b'1', 'OPEN', '演示自营店',
        '本机演示店铺', 5.00, 5.00, 5.00, 0, 0, b'0', b'0');
INSERT IGNORE INTO li_store_detail (id, store_id, store_name, goods_management_category, stock_warning, settlement_cycle)
VALUES (9100000000000002003, '9100000000000002002', '演示自营店', '9100000000000002011', 0, '');
INSERT IGNORE INTO li_clerk (id, create_by, create_time, delete_flag, clerk_name, member_id, store_id, shopkeeper, is_super, status)
VALUES (9100000000000002004, 'liroam-demo', NOW(6), b'0', 'liroam_demo_seller', '9100000000000002001', '9100000000000002002', b'1', b'1', b'1');
INSERT IGNORE INTO li_category (id, create_by, create_time, delete_flag, commission_rate, level, name, parent_id, sort_order, support_channel) VALUES
    (9100000000000002011, 'liroam-demo', NOW(6), b'0', 0.00, 0, '旅行通讯', 0, 1, b'0'),
    (9100000000000002012, 'liroam-demo', NOW(6), b'0', 0.00, 1, '境外上网卡', 9100000000000002011, 1, b'0'),
    (9100000000000002013, 'liroam-demo', NOW(6), b'0', 0.00, 2, '旅行卡', 9100000000000002012, 1, b'0');
SET @store_id := '9100000000000002002';
SET @store_name := (SELECT store_name FROM li_store WHERE id = @store_id);
SET @category_path := '9100000000000002011,9100000000000002012,9100000000000002013';

INSERT IGNORE INTO li_goods (id, create_by, create_time, delete_flag, update_time, brand_id, buy_count, category_path, comment_num, cost,
                             goods_name, goods_unit, grade, intro, auth_flag, market_enable, price, quantity, recommend, sales_model,
                             self_operated, store_id, store_name, selling_point, sn, template_id, weight, params, goods_type)
SELECT d.goods_id, 'liroam-demo', NOW(6), b'0', NOW(6), '0', 0, @category_path, 0, 0,
       d.goods_name, '件', 100, '<p>演示商品：使用区域与限制以页面「使用区域说明」为准。</p>', 'PASS', 'UPPER', d.price, 999, b'0', 'RETAIL',
       b'1', @store_id, @store_name,
       CASE d.form WHEN 'PHYSICAL' THEN '寄送实体卡，插卡即用' WHEN 'SOFT' THEN '写入软卡设备即用' ELSE '扫码即装，到达即用' END,
       CONCAT('LRDEMO', d.goods_id % 10000), '0', 0, '[]', d.goods_type
FROM demo_sku d;

INSERT IGNORE INTO li_goods_sku (id, create_by, create_time, delete_flag, update_time, brand_id, buy_count, virtual_sales, category_path,
                                 comment_num, cost, freight_payer, freight_template_id, goods_id, goods_name, goods_unit, auth_flag,
                                 promotion_flag, market_enable, price, quantity, recommend, sales_model, self_operated, store_id, store_name,
                                 selling_point, sn, specs, weight, simple_specs, goods_type, alert_quantity)
SELECT d.sku_id, 'liroam-demo', NOW(6), b'0', NOW(6), '0', 0, 0, @category_path,
       0, 0, 'STORE', '0', d.goods_id, d.goods_name, '件', 'PASS',
       b'0', 'UPPER', d.price, 999, b'0', 'RETAIL', b'1', @store_id, @store_name,
       CASE d.form WHEN 'PHYSICAL' THEN '寄送实体卡，插卡即用' WHEN 'SOFT' THEN '写入软卡设备即用' ELSE '扫码即装，到达即用' END,
       CONCAT('LRDEMO', d.sku_id % 10000), '{"images":[]}', 0, '', d.goods_type, 0
FROM demo_sku d;

-- 套餐映射与 SKU 绑定：卡产品取各供应商资费表里的资源（card_type）；资费表未导入时这里不插任何行。
INSERT INTO lr_supplier_offering (supplier_id, card_product_id, region_id, plan_template_id, kind, upstream_package_code)
SELECT s.id, p.id, r.id, d.plan_id, IF(d.sku_kind = 'TOP_UP', 'TOP_UP', 'BASE'), d.package_code
FROM demo_sku d
JOIN lr_supplier s ON s.code = d.supplier
JOIN lr_card_product p ON p.supplier_id = s.id AND p.card_type_code = d.card_type AND FIND_IN_SET(d.form, p.allowed_forms)
JOIN lr_usage_region r ON r.code = d.region
WHERE NOT EXISTS (SELECT 1 FROM lr_sku_binding b WHERE b.li_goods_sku_id = CAST(d.sku_id AS CHAR))
  AND NOT EXISTS (SELECT 1 FROM lr_supplier_offering o WHERE o.supplier_id = s.id AND o.card_product_id = p.id AND o.region_id = r.id
                  AND o.plan_template_id = d.plan_id AND o.upstream_package_code = d.package_code);

INSERT IGNORE INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, form, plan_template_id,
                                   offering_id, kyc_gate_mode, listing_state)
SELECT CAST(d.sku_id AS CHAR), CAST(d.goods_id AS CHAR), d.sku_kind, s.id, p.id, r.id, d.form, d.plan_id, o.id, d.kyc_gate, 'LISTED'
FROM demo_sku d
JOIN lr_supplier s ON s.code = d.supplier
JOIN lr_card_product p ON p.supplier_id = s.id AND p.card_type_code = d.card_type AND FIND_IN_SET(d.form, p.allowed_forms)
JOIN lr_usage_region r ON r.code = d.region
JOIN lr_supplier_offering o ON o.supplier_id = s.id AND o.card_product_id = p.id AND o.region_id = r.id
                           AND o.plan_template_id = d.plan_id AND o.upstream_package_code = d.package_code;

-- demo 命令据此逐个定价、导池卡（只读）。
SELECT b.id AS binding_id, b.li_goods_sku_id, s.code AS supplier, s.id AS supplier_id, b.card_product_id, d.price
FROM lr_sku_binding b JOIN lr_supplier s ON s.id = b.supplier_id JOIN demo_sku d ON CAST(d.sku_id AS CHAR) = b.li_goods_sku_id
ORDER BY b.li_goods_sku_id;
