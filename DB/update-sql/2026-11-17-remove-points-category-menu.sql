-- 去掉“积分分类”独立左侧菜单，积分商品分类已合并至积分商品页签。作者 Bulbasaur，创建日期 2026-11-17。
-- 积分商品菜单权限 /manager/promotion/pointsGoods* 已覆盖 /manager/promotion/pointsGoodsCategory*，无需修改 li_role_menu。
-- 不写死库名、不加 USE，可重复执行；执行后需刷新管理员菜单缓存或重新登录。
UPDATE li_menu
SET delete_flag = b'1'
WHERE id = '1407602673334636546'
  AND delete_flag = b'0';
