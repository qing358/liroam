-- 新增管理端与商家端单品立减入口，指定SKU每件减固定金额。
-- 复用li_full_discount，activity_type=SINGLE_REDUCTION，rules_json唯一规则的fullMinus为每件减额（元）。
-- li_promotion_goods使用SINGLE_REDUCTION类型，与满额/满件活动可以并存；不改动历史折扣活动。
-- 前置条件：已执行2026-11-09-full-discount-split-and-tiers.sql。不需要新增表或字段。
-- 可重复执行，保留已有同名/同路由菜单，不写li_role_menu，不自动扩大角色授权。
-- 验证：新菜单指向single-reduction组件；旧活动和已有配置保持原值。
-- 回退：先关闭单品立减活动，再回退代码及移除新菜单；保留业务记录，旧版本将忽略新类型。
-- @author Bulbasaur
-- @since 2026-11-14

INSERT INTO `li_menu`
    (`id`, `create_by`, `create_time`, `delete_flag`, `update_by`, `update_time`,
     `description`, `front_route`, `icon`, `level`, `name`, `parent_id`, `path`, `sort_order`,
     `title`, `front_component`, `permission`)
SELECT 2122380000000000002, 'Bulbasaur', NOW(), b'0', 'Bulbasaur', NOW(),
       '单品立减列表', 'promotions/full-discount/single-reduction', source.icon, source.level,
       'manager-single-reduction', source.parent_id, 'single-reduction',
       CAST(COALESCE(source.sort_order, 0) AS DECIMAL(10, 3)) + 0.04,
       '单品立减', source.front_component, source.permission
FROM `li_menu` source
WHERE (source.front_route = 'promotions/full-discount/full-discount' OR source.name IN ('full-cut-activity', 'full-discount'))
  AND source.delete_flag = b'0'
  AND NOT EXISTS (SELECT 1 FROM `li_menu` target WHERE target.name = 'manager-single-reduction' OR target.front_route = 'promotions/full-discount/single-reduction')
LIMIT 1;

INSERT INTO `li_store_menu`
    (`id`, `create_by`, `create_time`, `delete_flag`, `update_by`, `update_time`,
     `description`, `front_route`, `icon`, `level`, `name`, `parent_id`, `path`, `sort_order`,
     `title`, `permission`)
SELECT 2122380000000000012, 'Bulbasaur', NOW(), b'0', 'Bulbasaur', NOW(),
       '单品立减管理', 'promotion/full-discount/single-reduction', source.icon, source.level,
       'store-single-reduction', source.parent_id, 'single-reduction',
       CAST(COALESCE(source.sort_order, '0') AS DECIMAL(10, 3)) + 0.04,
       '单品立减', source.permission
FROM `li_store_menu` source
WHERE (source.front_route = 'promotion/full-discount/full-discount' OR source.name IN ('full-cut', 'full-discount'))
  AND source.delete_flag = b'0'
  AND NOT EXISTS (SELECT 1 FROM `li_store_menu` target WHERE target.name = 'store-single-reduction' OR target.front_route = 'promotion/full-discount/single-reduction')
LIMIT 1;
