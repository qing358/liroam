-- 新买家站帮助中心（apps/buyer-web /help）挂在壳文章分类上：根「帮助中心」+ 设计稿 15 的话题分类 + 常见问题。
-- id 固定，新站按 id 认（apps/buyer-web/app/services/help.ts 的 HELP_CATEGORY）；运营可改名、调排序、增删话题，文章在管理端「文章管理」维护。
-- 只补缺的行，已有同 id 的不动；库里没有 li_article_category（自有迁移的空库/测试库）时整段跳过。
-- 壳把分类树缓存在 Redis `{ARTICLE_CATEGORY}_`，迁移后须删该键（dev.mjs up 已做；生产见 docs/launch-checklist.md）。

SET @liroam_has_category := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_article_category');
SET @liroam_help_category_sql := IF(@liroam_has_category > 0,
    'INSERT INTO li_article_category (id, create_by, create_time, delete_flag, level, parent_id, sort, article_category_name, type)
     SELECT ?, ''liroam'', UTC_TIMESTAMP(), b''0'', ?, ?, ?, ?, ''OTHER'' FROM DUAL
     WHERE NOT EXISTS (SELECT 1 FROM li_article_category WHERE id = ?)',
    'DO ?, ?, ?, ?, ?, ?');
PREPARE liroam_help_category FROM @liroam_help_category_sql;

SET @root := '9100000000000003000';
SET @id := @root, @level := 0, @parent := '0', @sort := 2, @name := '帮助中心';
EXECUTE liroam_help_category USING @id, @level, @parent, @sort, @name, @id;

SET @level := 1, @parent := @root;
SET @id := '9100000000000003001', @sort := 1, @name := '设备兼容';
EXECUTE liroam_help_category USING @id, @level, @parent, @sort, @name, @id;
SET @id := '9100000000000003002', @sort := 2, @name := '安装与开卡';
EXECUTE liroam_help_category USING @id, @level, @parent, @sort, @name, @id;
SET @id := '9100000000000003003', @sort := 3, @name := '用量与加包';
EXECUTE liroam_help_category USING @id, @level, @parent, @sort, @name, @id;
SET @id := '9100000000000003004', @sort := 4, @name := '退款与售后';
EXECUTE liroam_help_category USING @id, @level, @parent, @sort, @name, @id;
SET @id := '9100000000000003005', @sort := 5, @name := '渠道订单认领';
EXECUTE liroam_help_category USING @id, @level, @parent, @sort, @name, @id;
SET @id := '9100000000000003006', @sort := 6, @name := '境外上网设置';
EXECUTE liroam_help_category USING @id, @level, @parent, @sort, @name, @id;
SET @id := '9100000000000003009', @sort := 9, @name := '常见问题';
EXECUTE liroam_help_category USING @id, @level, @parent, @sort, @name, @id;

DEALLOCATE PREPARE liroam_help_category;
