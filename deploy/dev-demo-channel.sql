-- 本机演示渠道：只由 `node scripts/dev.mjs demo` 在 dev-demo.sql 绑好 SKU 之后执行，幂等；不得进入任何正式环境迁移。
-- 渠道 JD 走模拟适配器 mock-jd（只在 dev/test 装配）；后台「渠道订单」页的模拟面板下单后，拉单即建卡、回传、对账。
-- 渠道 SKU JD-SKU-1001 映射到演示纯流量 eSIM（SUP_A 池卡），与模拟面板默认 SKU 一致。

SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

INSERT IGNORE INTO lr_channel (code, name, adapter_key, enabled, pull_cron, buyer_can_view_usage) VALUES
    ('JD', 'JD 渠道（模拟）', 'mock-jd', 1, '1m', 1);

INSERT IGNORE INTO lr_channel_sku_mapping (channel_id, external_sku_id, li_goods_sku_id, enabled)
SELECT c.id, 'JD-SKU-1001', b.li_goods_sku_id, 1
FROM lr_channel c
JOIN lr_sku_binding b ON b.li_goods_sku_id = '9100000000000001001'
WHERE c.code = 'JD';

SELECT c.code, c.adapter_key, m.external_sku_id, m.li_goods_sku_id
FROM lr_channel c LEFT JOIN lr_channel_sku_mapping m ON m.channel_id = c.id
WHERE c.code = 'JD';
