-- PC 平台首页（2115743299818201089）分类菜单的「自定义」占位分类换成按目的地分区（与 lilishop-ui/brand/decorate-mock.js 的 MOCK_MENU_PLACEHOLDER 一致）。
-- 组件 id 与整份占位分类都等于上游出厂值才改；运营改过任一项不动。占位只在编辑器里显示，PC 端遇到它改读真实商品分类。
-- 库里没有 li_page_data（自有迁移的空库/测试库）时整段跳过。

SET @liroam_has_page := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_page_data');
SET @liroam_pc_set_sql := IF(@liroam_has_page > 0,
    'UPDATE li_page_data SET page_data = JSON_SET(page_data, ?, CAST(? AS JSON))
      WHERE id = ? AND JSON_VALID(page_data) AND JSON_UNQUOTE(JSON_EXTRACT(page_data, ?)) = ? AND JSON_EXTRACT(page_data, ?) = CAST(? AS JSON)',
    'DO ?, ?, ?, ?, ?, ?, ?');
PREPARE liroam_pc_set FROM @liroam_pc_set_sql;

SET @page := 2115743299818201089;
SET @cmp := '$.list[2].components[0]', @cid := 'cmp_1793267508694_y5bsh';
SET @p := CONCAT(@cmp, '.options.categories'), @cp := CONCAT(@cmp, '.id');
SET @old := '[{"name":"精选频道","subCategories":[{"name":"每日精选","items":["新品尝鲜","大牌特惠","超级秒杀","今日热卖"]},{"name":"特色专区","items":["企业团购","会员专享","积分兑换","清仓特卖"]}]},
  {"name":"新品上架","subCategories":[{"name":"本周新发","items":["数码新品","秋季服饰","美妆尝新","家居收纳"]},{"name":"潮流榜单","items":["人气推荐","编辑精选","预售抢先"]}]},
  {"name":"企业采购","subCategories":[{"name":"办公采购","items":["办公用纸","文具收纳","商用打印","会议茶水"]},{"name":"员工福利","items":["节日礼盒","数码卡券","健康体检","员工关怀"]}]},
  {"name":"领券中心","subCategories":[{"name":"平台券","items":["全场满减","品类专享","运费券","会员特权"]},{"name":"大牌券","items":["数码大额券","生鲜补贴","美妆立减","母婴满减"]}]}]';
SET @new := '[{"name":"热门目的地","subCategories":[{"name":"日韩","items":["日本","韩国"]},{"name":"东南亚","items":["泰国","新加坡","马来西亚","越南"]}]},
  {"name":"欧美澳新","subCategories":[{"name":"欧洲","items":["欧洲多国","英国","法国"]},{"name":"美洲与大洋洲","items":["美国","加拿大","澳大利亚","新西兰"]}]},
  {"name":"港澳台","subCategories":[{"name":"港澳","items":["中国香港","中国澳门"]},{"name":"台湾","items":["中国台湾"]}]},
  {"name":"按卡型选","subCategories":[{"name":"卡型","items":["eSIM","实体卡","纯软卡"]},{"name":"续费加包","items":["流量加油包","续费套餐"]}]}]';
EXECUTE liroam_pc_set USING @p, @new, @page, @cp, @cid, @p, @old;

DEALLOCATE PREPARE liroam_pc_set;
