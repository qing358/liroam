-- 本机演示渠道：只由 `node scripts/dev.mjs demo` 在 dev-demo.sql 绑好 SKU 之后执行，幂等；不得进入任何正式环境迁移。
-- 渠道 JD / XHS 分别走模拟适配器 mock-jd / mock-xhs（只在 dev/test 装配）；后台「渠道订单」页选渠道后在模拟面板下单，拉单即建卡、回传、对账。
-- 两个渠道 SKU（JD-SKU-1001、XHS-SKU-1001）都映射到演示纯流量 eSIM（SUP_A 池卡）；模拟面板默认取该渠道的第一条映射。
-- XHS 演示「新渠道只加适配器 + 本表配置」：除 liroam-channel-xhs 模块与这几行外，渠道核心、履约、台账、页面都不改。

SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

INSERT IGNORE INTO lr_channel (code, name, adapter_key, enabled, pull_cron, buyer_can_view_usage) VALUES
    ('JD', 'JD 渠道（模拟）', 'mock-jd', 1, '1m', 1),
    ('XHS', 'XHS 渠道（模拟）', 'mock-xhs', 1, '1m', 1);

INSERT IGNORE INTO lr_channel_sku_mapping (channel_id, external_sku_id, li_goods_sku_id, enabled)
SELECT c.id, CONCAT(c.code, '-SKU-1001'), b.li_goods_sku_id, 1
FROM lr_channel c
JOIN lr_sku_binding b ON b.li_goods_sku_id = '9100000000000001001'
WHERE c.code IN ('JD', 'XHS');

SELECT c.code, c.adapter_key, m.external_sku_id, m.li_goods_sku_id
FROM lr_channel c LEFT JOIN lr_channel_sku_mapping m ON m.channel_id = c.id
WHERE c.code IN ('JD', 'XHS')
ORDER BY c.code;
