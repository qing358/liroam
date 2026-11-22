-- 新增管理端与商家端“多件优惠”入口，仅支持满件减钱，复用li_full_discount。
-- 件数门槛fullNum保存在rules_json中，activity_type为FULL_COUNT_MINUS，不改动历史活动及表结构。
-- 前置条件：已执行2026-11-09-full-discount-split-and-tiers.sql。
-- 可重复执行；保留已有同名/同路由菜单配置，不写入li_role_menu，不自动扩大角色授权。
-- 验证：两个菜单分别指向full-count-minus页面；旧活动类型、规则及数量保持不变。
-- 回退：停用新活动并移除新增菜单后再回退服务；保留新活动记录，不将件数转换为金额。
-- @author Bulbasaur
-- @since 2026-11-14

INSERT INTO `li_menu`
    (`id`, `create_by`, `create_time`, `delete_flag`, `update_by`, `update_time`,
     `description`, `front_route`, `icon`, `level`, `name`, `parent_id`, `path`, `sort_order`,
     `title`, `front_component`, `permission`)
SELECT 2122380000000000001, 'Bulbasaur', NOW(), b'0', 'Bulbasaur', NOW(),
       '多件优惠列表', 'promotions/full-discount/full-count-minus', source.icon, source.level,
       'manager-full-count-minus', source.parent_id, 'full-count-minus',
       CAST(COALESCE(source.sort_order, 0) AS DECIMAL(10, 3)) + 0.03,
       '多件优惠', source.front_component, source.permission
FROM `li_menu` source
WHERE (source.front_route = 'promotions/full-discount/full-discount' OR source.name IN ('full-cut-activity', 'full-discount'))
  AND source.delete_flag = b'0'
  AND NOT EXISTS (SELECT 1 FROM `li_menu` target WHERE target.name = 'manager-full-count-minus' OR target.front_route = 'promotions/full-discount/full-count-minus')
LIMIT 1;

INSERT INTO `li_store_menu`
    (`id`, `create_by`, `create_time`, `delete_flag`, `update_by`, `update_time`,
     `description`, `front_route`, `icon`, `level`, `name`, `parent_id`, `path`, `sort_order`,
     `title`, `permission`)
SELECT 2122380000000000011, 'Bulbasaur', NOW(), b'0', 'Bulbasaur', NOW(),
       '多件优惠管理', 'promotion/full-discount/full-count-minus', source.icon, source.level,
       'store-full-count-minus', source.parent_id, 'full-count-minus',
       CAST(COALESCE(source.sort_order, '0') AS DECIMAL(10, 3)) + 0.03,
       '多件优惠', source.permission
FROM `li_store_menu` source
WHERE (source.front_route = 'promotion/full-discount/full-discount' OR source.name IN ('full-cut', 'full-discount'))
  AND source.delete_flag = b'0'
  AND NOT EXISTS (SELECT 1 FROM `li_store_menu` target WHERE target.name = 'store-full-count-minus' OR target.front_route = 'promotion/full-discount/full-count-minus')
LIMIT 1;
