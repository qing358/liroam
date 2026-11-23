-- 品牌主题色：买家端平台主题（THEME_SETTING）仍是上游出厂红时换成轻游漫游青（主色）/ 日出橙（强调）/ 主色浅 3（辅助）。
-- 三个色值与 mode 都等于上游出厂值才改；运营在「主题设置」里选过的主题不动。库里没有 li_setting 时跳过。

SET @liroam_has_setting := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_setting');
SET @liroam_brand_theme_sql := IF(@liroam_has_setting > 0,
    'UPDATE li_setting
        SET setting_value = JSON_SET(setting_value, ''$.themeColor'', ?, ''$.lightColor'', ?, ''$.aiderLightColor'', ?)
      WHERE id = ''THEME_SETTING'' AND JSON_VALID(setting_value)
        AND JSON_UNQUOTE(JSON_EXTRACT(setting_value, ''$.mode'')) = ''system''
        AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(setting_value, ''$.themeColor''))) = ''#F5222D''
        AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(setting_value, ''$.lightColor''))) = ''#F5222D''
        AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(setting_value, ''$.aiderLightColor''))) = ''#FF4D4F''',
    'DO ?, ?, ?');
PREPARE liroam_brand_theme FROM @liroam_brand_theme_sql;
SET @theme := '#0B7A75', @light := '#E4572E', @aider := '#54A29E';
EXECUTE liroam_brand_theme USING @theme, @light, @aider;
DEALLOCATE PREPARE liroam_brand_theme;
