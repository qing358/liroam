-- 品牌文案：关于我们文章与页面装修欢迎语里仍是出厂值的换成轻游自己的介绍（只改壳表数据，不动结构）。
-- 关于我们：只在内容仍等于 V202611261000 写入的占位值时改写；运营改过的不动。
-- 页面装修：顶栏 / 用户权益组件的欢迎语仍是上游出厂值「欢迎来到商城」时换成「欢迎来到轻游」。
-- 库里没有 li_page_data / li_article（自有迁移的空库/测试库）时整段跳过。

SET @liroam_has_page := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_page_data');
SET @liroam_copy_page_sql := IF(@liroam_has_page > 0,
    'UPDATE li_page_data SET page_data = REPLACE(page_data, ?, ?) WHERE INSTR(page_data, ?) > 0',
    'DO ?, ?, ?');
PREPARE liroam_copy_page FROM @liroam_copy_page_sql;

SET @old := '"welcomeText":"欢迎来到商城"', @new := '"welcomeText":"欢迎来到轻游"';
EXECUTE liroam_copy_page USING @old, @new, @old;

DEALLOCATE PREPARE liroam_copy_page;

SET @liroam_has_article := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_article');
SET @liroam_copy_article_sql := IF(@liroam_has_article > 0,
    'UPDATE li_article SET content = ? WHERE id = ? AND content = ?',
    'DO ?, ?, ?');
PREPARE liroam_copy_article FROM @liroam_copy_article_sql;

SET @company := '想象智能（济南）有限公司';
SET @id := 1371780016521609216;
SET @old := CONCAT('<p>轻游 Liroam</p><p>', @company, '</p>');
SET @new := CONCAT(
    '<h3>关于轻游 Liroam</h3>',
    '<p>轻游是', @company, '运营的出境旅行通信卡平台。按目的地和行程天数选购 eSIM 与实体卡，',
    'eSIM 付款后在线获取激活码，实体卡发货后可查物流。</p>',
    '<p>一人可以持有多张卡，每张卡单独开卡、查看用量、续费和加包。</p>',
    '<h4>在轻游可以办理</h4><ul>',
    '<li><strong>按目的地选购</strong>：套餐标明使用区域、天数和流量，下单前即可核对。</li>',
    '<li><strong>在线开卡</strong>：eSIM 付款后发放激活码，实体卡发货后可查物流。</li>',
    '<li><strong>按卡管理</strong>：在「我的卡」查看开卡进度、已用流量和到期时间。</li>',
    '<li><strong>续费与加包</strong>：流量不够或行程延长，直接为原卡续费或加包。</li></ul>',
    '<p>在京东、小红书等渠道购买的轻游旅行卡，可凭订单号和下单手机号认领到「我的卡」统一管理。</p>');
EXECUTE liroam_copy_article USING @new, @id, @old;

DEALLOCATE PREPARE liroam_copy_article;
