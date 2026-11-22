-- 本机演示配置：只由 scripts/dev.mjs up 对本机 liroam 专用库执行，幂等；不得进入任何正式环境迁移。

-- 上游自带的本机验收开关：服务端跳过 LOGIN 场景滑块校验，供接口脚本直接登录（前端登录页仍会弹滑块）。
UPDATE li_setting
SET setting_value = JSON_SET(setting_value, '$.loginBypassEnabled', true)
WHERE id = 'VERIFICATION_SETTING';

-- 上游基线 li_verification_source 为空，前端登录滑块无法生成（至少需 1 张 RESOURCE、2 张 SLIDER）。
-- 素材取自上游 docker 仓库 561997f（2023-02-28）的 lilishop.sql，图片在上游公开 OSS。
INSERT IGNORE INTO `li_verification_source` VALUES (1374925624577097728, 'admin', '2021-03-24 22:26:28.439000', b'0', 'admin', '2021-03-25 04:37:15.833000', 'pic1', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/9bda22fa8bb34033922faeca931f53b9.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1374928342632890368, 'admin', '2021-03-24 22:37:16.474000', b'0', 'admin', '2021-03-25 04:40:15.272000', 'slider1', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/571cc482c3f54d378de12a07f5262934.png', 'SLIDER');
INSERT IGNORE INTO `li_verification_source` VALUES (1375018973833199616, 'admin', '2021-03-25 04:37:24.630000', b'0', NULL, NULL, 'pic2', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/a77deac53b5c48ff968b9936c8c64477.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019001842761728, 'admin', '2021-03-25 04:37:31.314000', b'0', NULL, NULL, 'pic3', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/87c783a7aae94600b92983f3b5713a94.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019029051211776, 'admin', '2021-03-25 04:37:37.801000', b'0', NULL, NULL, 'pic4', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/9e67a1150f7941e294330940505a8b6e.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019056037363712, 'admin', '2021-03-25 04:37:44.235000', b'0', NULL, NULL, 'pic5', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/c6033185c98a447c9876b8424996e89e.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019091248545792, 'admin', '2021-03-25 04:37:52.630000', b'0', NULL, NULL, 'pic6', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/e339a017f56b441db7d463462f0be918.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019124496793600, 'admin', '2021-03-25 04:38:00.557000', b'0', NULL, NULL, 'pic7', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/5fb387820c484dadbbf5ee116b95e1f7.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019153378770944, 'admin', '2021-03-25 04:38:07.443000', b'0', NULL, NULL, 'pic8', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/4c85191728a84bb5888a1bd1ed6e84e2.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019191098146816, 'admin', '2021-03-25 04:38:16.436000', b'0', NULL, NULL, 'pic9', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/c9cfbc77ce904f49945777fec9330f19.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019231891947520, 'admin', '2021-03-25 04:38:26.162000', b'0', NULL, NULL, 'pic10', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/89e6149e3e094e3685bab50df9f931de.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019260119613440, 'admin', '2021-03-25 04:38:32.892000', b'0', NULL, NULL, 'pic11', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/cd9cb96bfcc2448d98c8b62ba7e6c54a.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019287202234368, 'admin', '2021-03-25 04:38:39.349000', b'0', NULL, NULL, 'pic12', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/ecf765f942074b0bb9fecb07f06ea04a.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019324225355776, 'admin', '2021-03-25 04:38:48.176000', b'0', NULL, NULL, 'pic13', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/876e040152c5417fa210b0e081230081.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019354214629376, 'admin', '2021-03-25 04:38:55.326000', b'0', NULL, NULL, 'pic14', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/22b20912533d424cb792463c65c87086.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019402356850688, 'admin', '2021-03-25 04:39:06.804000', b'0', NULL, NULL, 'pic15', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/63698a4f91884de0b2101f0217c37f21.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019434212589568, 'admin', '2021-03-25 04:39:14.399000', b'0', NULL, NULL, 'pic16', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/2b051d6d62964eff85eb94469af774e4.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019466777165824, 'admin', '2021-03-25 04:39:22.163000', b'0', NULL, NULL, 'pic17', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/f53f1a83e67d4a829b83871b14556a80.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019499257856000, 'admin', '2021-03-25 04:39:29.907000', b'0', 'admin', '2021-06-21 00:32:17.551000', 'pic18', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/f4dba40016384c2ea7ec7e3fe14178e1.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019533244301312, 'admin', '2021-03-25 04:39:38.010000', b'0', NULL, NULL, 'pic19', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/ae1c3a94dd65492da685e8d795142811.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019562025615360, 'admin', '2021-03-25 04:39:44.872000', b'0', NULL, NULL, 'pic20', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/c64d0ca543d64e14a50e12eb4b4a6170.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019589565415424, 'admin', '2021-03-25 04:39:51.438000', b'0', NULL, NULL, 'pic21', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/3f01fd63b7634a7f892b096946ba92b1.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019614437638144, 'admin', '2021-03-25 04:39:57.368000', b'0', NULL, NULL, 'pic22', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/e0f5300053b64bf68c1e5a72da553f79.jpg', 'RESOURCE');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019769773686784, 'admin', '2021-03-25 04:40:34.403000', b'0', 'admin', '2021-07-13 18:48:35.397000', 'slider2', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/af08d2babd2a4573b4d0bfb1a77de9ec.png', 'SLIDER');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019802841579520, 'admin', '2021-03-25 04:40:42.287000', b'0', NULL, NULL, 'slider3', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/d0554cdd219d479593ef5ef8b13f5a35.png', 'SLIDER');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019834093338624, 'admin', '2021-03-25 04:40:49.738000', b'0', NULL, NULL, 'slider4', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/a0e910a6d8af44e0b1180fd61caa6a26.png', 'SLIDER');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019863013064704, 'admin', '2021-03-25 04:40:56.633000', b'0', NULL, NULL, 'slider5', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/5df7b1d5e2924fe9aa7625abeca8ecad.png', 'SLIDER');
INSERT IGNORE INTO `li_verification_source` VALUES (1375019899201519616, 'admin', '2021-03-25 04:41:05.261000', b'0', NULL, NULL, 'slider6', 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/1107d6be276849118353f8a63d387aea.png', 'SLIDER');
