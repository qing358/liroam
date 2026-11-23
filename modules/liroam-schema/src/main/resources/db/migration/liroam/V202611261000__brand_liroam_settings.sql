-- 品牌：壳设置、页面装修与协议 / 关于我们文章里仍是上游 Lilishop 出厂值的字段换成轻游 Liroam（只改壳表数据，不动结构）。
-- 逐字段比对上游出厂值，相等才改；运营改过的值、缺的键都不动。
-- logo / icon / 官网 / 公司链接 / ICP 号清空：三端回落到前端内置的轻游标识与 favicon，ICP 号待取得后由运营在后台填写。
-- 上游的部署地址（staticPageAddress、CONNECT_SETTING 等）不是品牌字段，按 launch-checklist 上线前替换。
-- 库里没有 li_setting / li_page_data / li_article（自有迁移的空库/测试库）时整段跳过。

SET @liroam_has_setting := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_setting');
SET @liroam_brand_setting_sql := IF(@liroam_has_setting > 0,
    'UPDATE li_setting SET setting_value = JSON_SET(setting_value, ?, ?)
     WHERE id = ? AND JSON_VALID(setting_value) AND JSON_UNQUOTE(JSON_EXTRACT(setting_value, ?)) = ?',
    'DO ?, ?, ?, ?, ?');
PREPARE liroam_brand_setting FROM @liroam_brand_setting_sql;

SET @company := '想象智能（济南）有限公司';
SET @upstream_logo := 'https://cdn.pickmall.cn/cdn/logo/logo2.png';
SET @upstream_icon := 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/default/388d86596b5342c2ba57c76b4f0f6a98.ico';

SET @id := 'BASE_SETTING';
SET @p := '$.siteName', @old := 'lilishop', @new := '轻游';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.copyrightOwner', @old := 'lilishop', @new := @company;
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.companyName', @old := '北京宏业汇成科技有限公司', @new := @company;
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.website', @old := 'https://pickmall.cn', @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.companyUrl', @old := 'https://pickmall.cn', @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.icp', @old := '京ICP备20009696号-1', @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.domainLogo', @old := @upstream_logo, @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.buyerSideLogo', @old := @upstream_logo, @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.storeSideLogo', @old := @upstream_logo, @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.domainIcon', @old := @upstream_icon, @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.buyerSideIcon', @old := @upstream_icon, @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @p := '$.storeSideIcon', @old := @upstream_icon, @new := '';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;

SET @id := 'VERIFICATION_SETTING', @p := '$.watermark', @old := 'LILI-SHOP', @new := 'LIROAM';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;
SET @id := 'SMS_SETTING', @p := '$.signName', @old := 'lili', @new := '轻游';
EXECUTE liroam_brand_setting USING @p, @new, @id, @p, @old;

DEALLOCATE PREPARE liroam_brand_setting;

SET @liroam_has_page := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_page_data');
SET @liroam_brand_page_sql := IF(@liroam_has_page > 0,
    'UPDATE li_page_data SET page_data = REPLACE(page_data, ?, ?) WHERE INSTR(page_data, ?) > 0',
    'DO ?, ?, ?');
PREPARE liroam_brand_page FROM @liroam_brand_page_sql;

SET @old := '"logoUrl":"https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/logo/logo.png"', @new := '"logoUrl":""';
EXECUTE liroam_brand_page USING @old, @new, @old;
SET @old := '"logoUrl":"https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/2112709632299044865/d8ff5b39a7724ab4973330543fa74056.png"', @new := '"logoUrl":""';
EXECUTE liroam_brand_page USING @old, @new, @old;
SET @old := '"title":"lilishop"', @new := '"title":"轻游"';
EXECUTE liroam_brand_page USING @old, @new, @old;

DEALLOCATE PREPARE liroam_brand_page;

SET @liroam_has_article := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_article');
SET @liroam_brand_article_sql := IF(@liroam_has_article > 0,
    'UPDATE li_article SET content = REPLACE(content, ?, ?) WHERE id = ? AND INSTR(content, ?) > 0',
    'DO ?, ?, ?, ?');
PREPARE liroam_brand_article FROM @liroam_brand_article_sql;

SET @id := 1371780016521609216;
SET @old := '<p>关于，关于我们</p><p><img src="https://lilishop-oss.oss-cn-beijing.aliyuncs.com/b280f3d1b3504cbeb0a8dc0fab3a24ba.png" /></p>';
SET @new := CONCAT('<p>轻游 Liroam</p><p>', @company, '</p>');
EXECUTE liroam_brand_article USING @old, @new, @id, @old;
SET @id := 1371779927900160000, @old := '以下隐私协议是xxx公司', @new := CONCAT('以下隐私协议是', @company);
EXECUTE liroam_brand_article USING @old, @new, @id, @old;

DEALLOCATE PREPARE liroam_brand_article;
