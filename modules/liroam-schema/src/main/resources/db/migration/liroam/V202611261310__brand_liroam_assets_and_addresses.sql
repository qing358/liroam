-- 上游图床与上游部署地址（只改壳表数据，不动结构；逐项比对上游出厂值，相等才改，运营改过的不动）。
-- 会员等级 / 权益 / 付费会员图标与 admin 头像：上游图床 lilishop-oss 换成本站静态素材（buyer、manager 的 public/liroam/）。
-- 部署地址：BASE_SETTING 静态页地址、联合登录回调与支付宝回调仍指向上游演示站 *.pickmall.cn 时清空；
-- 本机由 deploy/dev-seed.sql 按 deploy/ports.env 填本机地址，正式环境按 docs/launch-checklist.md 填真实域名。
-- 库里没有对应上游表（自有迁移的空库/测试库）时跳过。

SET @upstream_oss := 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/2117947015370043393/';

SET @liroam_has_setting := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_setting');
SET @liroam_setting_sql := IF(@liroam_has_setting > 0,
    'UPDATE li_setting SET setting_value = JSON_SET(setting_value, ?, ?)
     WHERE id = ? AND JSON_VALID(setting_value) AND JSON_UNQUOTE(JSON_EXTRACT(setting_value, ?)) = ?',
    'DO ?, ?, ?, ?, ?');
PREPARE liroam_setting FROM @liroam_setting_sql;

SET @id := 'BASE_SETTING', @new := '';
SET @p := '$.staticPageAddress', @old := 'https://pc-b2b2c.pickmall.cn';
EXECUTE liroam_setting USING @p, @new, @id, @p, @old;
SET @p := '$.staticPageWapAddress', @old := 'https://m-b2b2c.pickmall.cn';
EXECUTE liroam_setting USING @p, @new, @id, @p, @old;
SET @id := 'CONNECT_SETTING';
SET @p := '$.callbackUrl', @old := 'https://buyer-api.pickmall.cn';
EXECUTE liroam_setting USING @p, @new, @id, @p, @old;
SET @p := '$.pc', @old := 'https://pc-b2b2c.pickmall.cn';
EXECUTE liroam_setting USING @p, @new, @id, @p, @old;
SET @p := '$.wap', @old := 'https://m-b2b2c.pickmall.cn';
EXECUTE liroam_setting USING @p, @new, @id, @p, @old;
SET @id := 'ALIPAY_PAYMENT', @p := '$.callbackUrl', @old := 'https://buyer-api.pickmall.cn';
EXECUTE liroam_setting USING @p, @new, @id, @p, @old;

SET @id := 'PAID_MEMBER_SETTING', @p := '$.icon';
SET @old := CONCAT(@upstream_oss, '28bf6388af504bf6990bbdc6ba296806.png'), @new := '/liroam/member/paid.svg';
EXECUTE liroam_setting USING @p, @new, @id, @p, @old;

DEALLOCATE PREPARE liroam_setting;

SET @liroam_has_grade := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_member_grade');
SET @liroam_grade_sql := IF(@liroam_has_grade > 0,
    'UPDATE li_member_grade SET grade_image = ? WHERE id = ? AND grade_image = ?',
    'DO ?, ?, ?');
PREPARE liroam_grade FROM @liroam_grade_sql;
SET @id := 1, @new := '/liroam/member/grade-1.svg', @old := CONCAT(@upstream_oss, '4c9dee6a9f3a49e29149e2a73f915122.png');
EXECUTE liroam_grade USING @new, @id, @old;
SET @id := 2, @new := '/liroam/member/grade-2.svg', @old := CONCAT(@upstream_oss, '7bbe054695754b9fbf18fa17fefcd542.png');
EXECUTE liroam_grade USING @new, @id, @old;
SET @id := 3, @new := '/liroam/member/grade-3.svg', @old := CONCAT(@upstream_oss, '6be4279660174865a6e066986bb6e35e.png');
EXECUTE liroam_grade USING @new, @id, @old;
SET @id := 4, @new := '/liroam/member/grade-4.svg', @old := CONCAT(@upstream_oss, '14e1d00ea53245f78ed213cf334f41f4.png');
EXECUTE liroam_grade USING @new, @id, @old;
DEALLOCATE PREPARE liroam_grade;

SET @liroam_has_benefit := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_member_benefit');
SET @liroam_benefit_sql := IF(@liroam_has_benefit > 0,
    'UPDATE li_member_benefit SET benefit_logo = ? WHERE id = ? AND benefit_logo = ?',
    'DO ?, ?, ?');
PREPARE liroam_benefit FROM @liroam_benefit_sql;
SET @id := 1, @new := '/liroam/member/benefit-badge.svg', @old := CONCAT(@upstream_oss, 'e629982542f64f3db75020e5e842788c.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 2, @new := '/liroam/member/benefit-service.svg', @old := CONCAT(@upstream_oss, '88d48600589c41d7b97a5a35097cf4e0.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 3, @new := '/liroam/member/benefit-note.svg', @old := CONCAT(@upstream_oss, 'cf34dbbda09946489bf5b0231860d012.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 4, @new := '/liroam/member/benefit-points.svg', @old := CONCAT(@upstream_oss, '58e629e0915e481181ca574371688759.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 5, @new := '/liroam/member/benefit-points.svg', @old := CONCAT(@upstream_oss, '35b640facdc543db9b57a2b47643816a.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 6, @new := '/liroam/member/benefit-gift.svg', @old := CONCAT(@upstream_oss, '54cf95fa0ed6400eba268181d4d43401.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 7, @new := '/liroam/member/benefit-gift.svg', @old := CONCAT(@upstream_oss, 'c639048c3fb5415a9e0f8b32c6a69f4b.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 8, @new := '/liroam/member/benefit-gift.svg', @old := CONCAT(@upstream_oss, 'a5243dbf74d94cc982ee28641c66c3b4.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 9, @new := '/liroam/member/benefit-note.svg', @old := CONCAT(@upstream_oss, '26898929300f40efb9fb3edd05937d74.png');
EXECUTE liroam_benefit USING @new, @id, @old;
SET @id := 10, @new := '/liroam/member/benefit-note.svg', @old := CONCAT(@upstream_oss, 'c94b9dac3d2a41dc9070b23c9237e3b3.png');
EXECUTE liroam_benefit USING @new, @id, @old;
DEALLOCATE PREPARE liroam_benefit;

SET @liroam_has_admin := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_admin_user');
SET @liroam_admin_sql := IF(@liroam_has_admin > 0,
    'UPDATE li_admin_user SET avatar = ? WHERE id = ? AND avatar = ?',
    'DO ?, ?, ?');
PREPARE liroam_admin FROM @liroam_admin_sql;
SET @id := 1337306110277476352, @new := '/liroam/avatar-admin.svg';
SET @old := 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/65e87ffa718b42bb9c201712566dbc9a.png';
EXECUTE liroam_admin USING @new, @id, @old;
DEALLOCATE PREPARE liroam_admin;
