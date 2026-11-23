-- 平台首页装修换成轻游内容（只改壳表 li_page_data 数据，不动结构）：上游样例商品、店铺名、轮播与活动卡图、电商热词与上游配色。
-- PC 平台首页（2115743299818201089）逐组件改：组件 id 与原值都等于上游出厂值才改，运营改过的组件不动。
--   搜索热词换出境目的地；分类菜单悬停色换漫游青；轮播与活动卡换本站静态素材（各前端 public/liroam/decorate/）；
--   标题栏与商品列表去掉上游分类 id（商品列表改为按上架商品自动取数）；整排上游测试商品快照的「推荐商品」移除，由运营选真实商品后再加。
-- H5 平台首页（2112712039703707649）是上游「双十一活动」整页样例：名称、配色、搜索词、公告与首个横幅都还是出厂值时整页换成轻游 H5 首页。
-- 库里没有 li_page_data（自有迁移的空库/测试库）时整段跳过。

SET @liroam_has_page := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_page_data');
SET @liroam_pc_set_sql := IF(@liroam_has_page > 0,
    'UPDATE li_page_data SET page_data = JSON_SET(page_data, ?, CAST(? AS JSON))
      WHERE id = ? AND JSON_VALID(page_data) AND JSON_UNQUOTE(JSON_EXTRACT(page_data, ?)) = ? AND JSON_EXTRACT(page_data, ?) = CAST(? AS JSON)',
    'DO ?, ?, ?, ?, ?, ?, ?');
PREPARE liroam_pc_set FROM @liroam_pc_set_sql;

SET @page := 2115743299818201089;

SET @cmp := '$.list[1].components[0]', @cid := 'cmp_1793267497509_3igj2';
SET @p := CONCAT(@cmp, '.options.hotWords'), @cp := CONCAT(@cmp, '.id');
SET @old := '["手机", "数码家电", "秋季上新", "电脑整机", "智能手表"]', @new := '["日本", "韩国", "泰国", "欧洲多国", "美国"]';
EXECUTE liroam_pc_set USING @p, @new, @page, @cp, @cid, @p, @old;

SET @cmp := '$.list[2].components[0]', @cid := 'cmp_1793267508694_y5bsh';
SET @p := CONCAT(@cmp, '.options.activeColor'), @cp := CONCAT(@cmp, '.id');
SET @old := '"#409EFF"', @new := '"#0B7A75"';
EXECUTE liroam_pc_set USING @p, @new, @page, @cp, @cid, @p, @old;

SET @cmp := '$.list[2].components[1]', @cid := 'cmp_1793267517026_l7o7t';
SET @p := CONCAT(@cmp, '.options.list'), @cp := CONCAT(@cmp, '.id'), @g := CONCAT(@cmp, '.options.list[0].img');
SET @old := '"https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/carousel/banner_1.png"';
SET @new := '[{"img": "/liroam/decorate/banner-1.svg", "title": "出境上网，出发前备好", "url": "/goodsList"},
  {"img": "/liroam/decorate/banner-2.svg", "title": "eSIM 付款后在线发码", "url": "/goodsList"},
  {"img": "/liroam/decorate/banner-3.svg", "title": "一人多卡，按卡管理", "url": "/home/MyTravelCards"}]';
EXECUTE liroam_pc_set USING @p, @new, @page, @cp, @cid, @g, @old;

SET @cmp := '$.list[3].components[0]', @cid := 'cmp_1793267546877_v2psi';
SET @p := CONCAT(@cmp, '.options.cards'), @cp := CONCAT(@cmp, '.id'), @g := CONCAT(@cmp, '.options.cards[0].bgImg');
SET @old := '"https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/2115749184804200449/ea9801b8016e492eb8c87c328b9a08c8.jpg"';
SET @new := '[{"title": "限时特价", "subtitle": "热门套餐限时优惠", "bgImg": "/liroam/decorate/promo-1.svg", "url": "/seckill"},
  {"title": "新上架", "subtitle": "新开通目的地套餐", "bgImg": "/liroam/decorate/promo-2.svg", "url": "/goodsList"},
  {"title": "续费加包", "subtitle": "原卡在线办理，无需换卡", "bgImg": "/liroam/decorate/promo-3.svg", "url": "/home/MyTravelCards"},
  {"title": "领券中心", "subtitle": "下单前先领券", "bgImg": "/liroam/decorate/promo-4.svg", "url": "/coupon"}]';
EXECUTE liroam_pc_set USING @p, @new, @page, @cp, @cid, @g, @old;

SET @cmp := '$.list[4].components[0]', @cid := 'cmp_1793268768622_c9uif';
SET @p := CONCAT(@cmp, '.options'), @cp := CONCAT(@cmp, '.id'), @g := CONCAT(@cmp, '.options.moreUrl');
SET @old := '"/goodsList?categoryId=1348576427268399274"';
SET @new := '{"mainTitle": "热门套餐", "subtitle": "按目的地和天数选购", "align": "left", "showMore": true, "moreText": "查看更多 >", "moreUrl": "/goodsList"}';
EXECUTE liroam_pc_set USING @p, @new, @page, @cp, @cid, @g, @old;

SET @cmp := '$.list[5].components[0]', @cid := 'cmp_1793267810637_mbkox';
SET @p := CONCAT(@cmp, '.options'), @cp := CONCAT(@cmp, '.id'), @g := CONCAT(@cmp, '.options.categoryId');
SET @old := '"1348576427268399555"';
SET @new := '{"dataSource": "category", "categoryIds": [], "goodsCount": 12, "goods": [], "columns": 6, "showTitle": true, "showPrice": true,
  "showOriginalPrice": true, "showCartBtn": true, "showTags": true, "sortType": "default", "categoryId": "", "categoryName": ""}';
EXECUTE liroam_pc_set USING @p, @new, @page, @cp, @cid, @g, @old;

DEALLOCATE PREPARE liroam_pc_set;

SET @liroam_pc_drop_sql := IF(@liroam_has_page > 0,
    'UPDATE li_page_data SET page_data = JSON_REMOVE(page_data, ?)
      WHERE id = ? AND JSON_VALID(page_data) AND JSON_UNQUOTE(JSON_EXTRACT(page_data, ?)) = ? AND JSON_UNQUOTE(JSON_EXTRACT(page_data, ?)) = ?',
    'DO ?, ?, ?, ?, ?, ?');
PREPARE liroam_pc_drop FROM @liroam_pc_drop_sql;
SET @row := '$.list[6]', @rid := '$.list[6].id', @rv := 'row_1793267647547_4i51u';
SET @g := '$.list[6].components[0].options.goods[0].id', @gv := '1945777974252118018';
EXECUTE liroam_pc_drop USING @row, @page, @rid, @rv, @g, @gv;
DEALLOCATE PREPARE liroam_pc_drop;

SET @liroam_h5_sql := IF(@liroam_has_page > 0,
    'UPDATE li_page_data SET name = ?, page_data = ?
      WHERE id = ? AND name = ? AND JSON_VALID(page_data)
        AND JSON_UNQUOTE(JSON_EXTRACT(page_data, ''$.name'')) = ? AND JSON_UNQUOTE(JSON_EXTRACT(page_data, ''$.topColor'')) = ?
        AND JSON_UNQUOTE(JSON_EXTRACT(page_data, ''$.list[0].options.placeWords'')) = ?
        AND JSON_UNQUOTE(JSON_EXTRACT(page_data, ''$.list[0].options.logoFixUrl'')) = ?',
    'DO ?, ?, ?, ?, ?, ?, ?, ?');
PREPARE liroam_h5 FROM @liroam_h5_sql;
SET @page := 2112712039703707649, @name := '平台首页', @old_name := '双十一活动', @old_top := '#C80A1E', @old_words := '双11跨店每满300减50';
SET @old_logo := 'https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/2112709632299044865/2501af29fb6244488d9b84c4035b307c.png';
SET @new := '{"name":"平台首页","title":"轻游","bgType":"color","bgColor":"#EEF6F5","bgImage":"","bgFill":"stretch","topColor":"#0B7A75","titleColor":"white","list":[{"type":"homeComb","name":"头部组件","icon":"Menu","group":"base","notAdd":true,"notImg":true,"notLink":true,"key":"1792544137131_3784","model":"homeComb_1792544137131_3784","options":{"searType":"logo","logoUrl":"","logoFixUrl":"","placeWords":"搜索目的地，如 日本、欧洲","hotWords":["日本","韩国","泰国","欧洲多国","美国"],"hotInterval":3,"textColor":"#303133","boxColor":"#FFFFFF","textAlign":"left","searchRadius":30,"showTabs":false,"tabColor":"#FFFFFF","tabActiveColor":"#FFFFFF","swiperStyle":"card","indicatorStyle":"line","indicatorPosition":"center","indicatorColor":"#FFFFFF","bannerRadius":16,"pageGap":12,"tabList":[],"list":[{"img":"/liroam/decorate/h5-banner-1.svg","url":"","link":"","size":"750*332","model":"link"},{"img":"/liroam/decorate/h5-banner-2.svg","url":"","link":"","size":"750*332","model":"link"},{"img":"/liroam/decorate/h5-banner-3.svg","url":"","link":"","size":"750*332","model":"link"}]}},{"type":"notice","name":"公告","icon":"Bell","group":"base","img":"md-image","notAdd":false,"notLink":true,"notImg":true,"key":"1792544676202_78562","model":"notice_1792544676202_78562","options":{"bgColor1":"#FFFFFF","bgColor2":"#FFFFFF","paddingTop":6,"paddingBottom":6,"paddingX":20,"pageGap":16,"borderRadius":16,"itemColor":"#333333","list":[{"title":[{"context":"日本、韩国、泰国等热门目的地套餐已上架，可按天数选购","url":""},{"context":"「我的卡」支持在线续费与加包，无需换卡","url":""},{"context":"实名类套餐请在开卡前完成实名认证","url":""}],"color":"#0B7A75","bk_color":"#FFF","direction":"horizontal","img":""}]}},{"type":"coupon","name":"优惠券","icon":"Ticket","group":"marketing","img":"md-image","key":"1792546623590_72446","model":"coupon_1792546623590_72446","options":{"layoutStyle":"1","bgColor":"#FFFFFF","bgColor1":"#FFFFFF","bgColor2":"#FCEEEA","paddingTop":16,"paddingBottom":16,"contentGap":16,"paddingX":20,"pageGap":16,"borderRadius":20,"list":[{"title":"先领券再下单","count":5,"data":[{"id":1,"couponType":"PRICE","price":70,"consumeThreshold":500,"storeName":"platform"},{"id":2,"couponType":"PRICE","price":50,"consumeThreshold":300,"storeName":"platform"},{"id":3,"couponType":"PRICE","price":30,"consumeThreshold":200,"storeName":"platform"},{"id":4,"couponType":"PRICE","price":20,"consumeThreshold":100,"storeName":"platform"},{"id":5,"couponType":"PRICE","price":10,"consumeThreshold":0,"storeName":"platform"}]}]}},{"notAdd":true,"notImg":true,"notLink":true,"drawerPromotions":true,"group":"marketing","type":"promotionDetail","promotionsType":"SECKILL","name":"秒杀活动","icon":"Timer","subBkColor":"#E4572E","subName":"x点场","subColor":"#fff","img":"md-image","key":"1792546812696_89889","model":"promotionDetail_1792546812696_89889","options":{"layoutStyle":"1","bgColor1":"#0B7A75","bgColor2":"#FFFFFF","titleColor":"#FFFFFF","moreColor":"#282828","nameColor":"#282828","paddingTop":20,"paddingBottom":20,"contentGap":16,"paddingX":20,"pageGap":16,"borderRadius":20,"imgRadius":12,"showGoodsName":true,"showGoodsPrice":true,"showGroupTag":true,"showOriginalPrice":true,"showGroupBtn":true,"headBgType":"color","headBgColor1":"#0B7A75","headBgColor2":"#54A29E","headTitleType":"text","list":[{"type":"SECKILL","title":"限时特价","title1":"热门套餐限时优惠","bk_color":"#E4572E","color1":"#fff","data":[]}]}},{"type":"title","name":"标题栏","icon":"Collection","group":"tools","img":"md-image","notAdd":true,"notImg":true,"key":"1792547668045_18034","model":"title_1792547668045_18034","options":{"paddingTop":10,"paddingBottom":6,"paddingX":12,"pageGap":8,"radiusTop":16,"radiusBottom":16,"color":"#282828","color1":"#282828","moreColor":"#282828","list":[{"title":"热门套餐","color":"#102A43","title1":"按目的地和天数选购","color1":"#C8461F","bk_color":"#FFFFFF","textAlign":"left","link":"","url":"","model":"link","showMore":true,"moreText":"全部套餐","bgType":"color","fontSize":18,"subFontSize":12,"moreFontSize":12,"moreColor":"#999999","textStyle":"bold"}]}},{"type":"goods","name":"商品列表","icon":"Goods","group":"base","img":"md-image","notAdd":true,"notLink":true,"notImg":true,"key":"1792547732798_34044","model":"goods_1792547732798_34044","options":{"bgColor1":"#EEF6F5","bgColor2":"#EEF6F5","titleColor":"#282828","toneMode":"theme","priceColor":"#C8461F","salesColor":"#888888","paddingTop":8,"paddingBottom":20,"paddingX":20,"contentGap":16,"pageGap":16,"borderRadius":0,"contentRadius":16,"list":[{"layout":"card","showName":true,"showPrice":true,"showSales":true,"cartShow":true,"cartStyle":"cart","cartAction":"detail","dataMode":"category","categoryId":"","brandId":"","storeId":"","goodsList":[],"count":10,"sortType":"default"}]}}]}';
EXECUTE liroam_h5 USING @name, @new, @page, @old_name, @old_name, @old_top, @old_words, @old_logo;
DEALLOCATE PREPARE liroam_h5;
