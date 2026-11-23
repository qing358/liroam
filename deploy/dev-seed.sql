-- 本机演示配置：只由 scripts/dev.mjs up 对本机 liroam 专用库执行，幂等；不得进入任何正式环境迁移。

-- 上游自带的本机验收开关：服务端跳过 LOGIN 场景滑块校验，供接口脚本直接登录（前端登录页仍会弹滑块）。
UPDATE li_setting
SET setting_value = JSON_SET(setting_value, '$.loginBypassEnabled', true)
WHERE id = 'VERIFICATION_SETTING';

-- 上游基线 li_verification_source 为空，前端登录滑块无法生成（至少需 1 张 RESOURCE、2 张 SLIDER）。
-- 素材为本仓 deploy/dev-assets/verification/（logo/tools/assets.py 生成），后端按 file:// 读本机文件；正式环境上传到自有 OSS（docs/launch-checklist.md）。
-- 早期 dev-seed 写入的上游图床素材（docker 仓 561997f 的 28 行）按 id 与图床前缀删掉。
DELETE FROM `li_verification_source`
WHERE resource LIKE 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/%'
  AND id IN (1374925624577097728, 1374928342632890368, 1375018973833199616, 1375019001842761728, 1375019029051211776, 1375019056037363712, 1375019091248545792, 1375019124496793600, 1375019153378770944, 1375019191098146816, 1375019231891947520, 1375019260119613440, 1375019287202234368, 1375019324225355776, 1375019354214629376, 1375019402356850688, 1375019434212589568, 1375019466777165824, 1375019499257856000, 1375019533244301312, 1375019562025615360, 1375019589565415424, 1375019614437638144, 1375019769773686784, 1375019802841579520, 1375019834093338624, 1375019863013064704, 1375019899201519616);
INSERT INTO `li_verification_source` (id, create_by, create_time, delete_flag, name, resource, type) VALUES
  (1999000000000000001, 'liroam', '2026-11-26 13:10:00', b'0', '轻游背景1', CONCAT(@liroam_dev_assets, '/verification/bg-1.jpg'), 'RESOURCE'),
  (1999000000000000002, 'liroam', '2026-11-26 13:10:00', b'0', '轻游背景2', CONCAT(@liroam_dev_assets, '/verification/bg-2.jpg'), 'RESOURCE'),
  (1999000000000000003, 'liroam', '2026-11-26 13:10:00', b'0', '轻游背景3', CONCAT(@liroam_dev_assets, '/verification/bg-3.jpg'), 'RESOURCE'),
  (1999000000000000004, 'liroam', '2026-11-26 13:10:00', b'0', '轻游背景4', CONCAT(@liroam_dev_assets, '/verification/bg-4.jpg'), 'RESOURCE'),
  (1999000000000000005, 'liroam', '2026-11-26 13:10:00', b'0', '轻游背景5', CONCAT(@liroam_dev_assets, '/verification/bg-5.jpg'), 'RESOURCE'),
  (1999000000000000006, 'liroam', '2026-11-26 13:10:00', b'0', '轻游背景6', CONCAT(@liroam_dev_assets, '/verification/bg-6.jpg'), 'RESOURCE'),
  (1999000000000000007, 'liroam', '2026-11-26 13:10:00', b'0', '轻游拼块1', CONCAT(@liroam_dev_assets, '/verification/slider-1.png'), 'SLIDER'),
  (1999000000000000008, 'liroam', '2026-11-26 13:10:00', b'0', '轻游拼块2', CONCAT(@liroam_dev_assets, '/verification/slider-2.png'), 'SLIDER'),
  (1999000000000000009, 'liroam', '2026-11-26 13:10:00', b'0', '轻游拼块3', CONCAT(@liroam_dev_assets, '/verification/slider-3.png'), 'SLIDER'),
  (1999000000000000010, 'liroam', '2026-11-26 13:10:00', b'0', '轻游拼块4', CONCAT(@liroam_dev_assets, '/verification/slider-4.png'), 'SLIDER')
ON DUPLICATE KEY UPDATE resource = VALUES(resource), delete_flag = VALUES(delete_flag);

-- 上游演示站 *.pickmall.cn 已由 Flyway 清空（V202611261310）；本机填买家 PC 与 buyer-api 的登记地址，运营在本机改过的不动。
-- 本机没有 H5 端，wap 地址留空。
UPDATE li_setting SET setting_value = JSON_SET(setting_value, '$.staticPageAddress', @liroam_buyer_pc)
WHERE id = 'BASE_SETTING' AND JSON_UNQUOTE(JSON_EXTRACT(setting_value, '$.staticPageAddress')) IN ('', 'https://pc-b2b2c.pickmall.cn');
UPDATE li_setting SET setting_value = JSON_SET(setting_value, '$.callbackUrl', @liroam_buyer_api)
WHERE id IN ('CONNECT_SETTING', 'ALIPAY_PAYMENT') AND JSON_UNQUOTE(JSON_EXTRACT(setting_value, '$.callbackUrl')) IN ('', 'https://buyer-api.pickmall.cn');
UPDATE li_setting SET setting_value = JSON_SET(setting_value, '$.pc', @liroam_buyer_pc)
WHERE id = 'CONNECT_SETTING' AND JSON_UNQUOTE(JSON_EXTRACT(setting_value, '$.pc')) IN ('', 'https://pc-b2b2c.pickmall.cn');
