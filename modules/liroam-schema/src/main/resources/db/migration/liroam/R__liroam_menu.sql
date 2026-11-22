-- 管理端「旅行卡」菜单（壳表 li_menu，三级：顶部导航 level 0 → 侧栏分组 level 1 → 页面 level 2）。
-- 页面 front_route 指向 manager/src/views/ 下的组件路径（不含 .vue）；path 为路由路径，name 为路由名，全局唯一。
-- 固定 id 号段 9100000000000000100–9100000000000000199 归 liroam 菜单专用：上游 li_menu 现有 id 在 1.0e17–2.2e18
-- （雪花 id 与上游 update-sql 手写 id），9.1e18 远离两者。新增菜单在本号段内顺延，不复用已删 id。
-- 幂等：固定 id + ON DUPLICATE KEY UPDATE，每次改动本文件 Flyway 重放一次，菜单回到本文件口径。
-- 超级管理员自动看到全部菜单；不写 li_role_menu，普通角色由运营在「角色管理」里授权（与上游 update-sql 一致，不自动扩大授权）。
-- permission 为非超管角色的接口放行前缀（逗号分隔，上游 ManagerTokenGenerate 按此授权）。
-- 库里没有 li_menu（自有迁移的空库/测试库）时整段跳过。

CREATE TEMPORARY TABLE lr_tmp_menu_seed (
    id          BIGINT       NOT NULL PRIMARY KEY,
    level       INT          NOT NULL,
    name        VARCHAR(255) NOT NULL,
    title       VARCHAR(255) NOT NULL,
    parent_id   VARCHAR(255) NOT NULL,
    path        VARCHAR(255) NULL,
    front_route VARCHAR(255) NULL,
    icon        VARCHAR(255) NULL,
    sort_order  DECIMAL(10, 2) NOT NULL,
    permission  VARCHAR(255) NULL,
    description VARCHAR(255) NULL
) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci;

INSERT INTO lr_tmp_menu_seed (id, level, name, title, parent_id, path, front_route, icon, sort_order, permission, description) VALUES
    (9100000000000000100, 0, 'liroam', '旅行卡', '0', NULL, NULL, NULL, 0.65, NULL, 'liroam 旅行卡'),

    (9100000000000000110, 1, 'liroam-fulfillment', '卡与履约', '9100000000000000100', '/', 'Main', 'ios-card', 1, NULL, 'liroam 卡与履约'),
    (9100000000000000111, 2, 'liroamCards', '卡台账', '9100000000000000110', 'liroam-cards', 'liroam/cards/CardLedger', 'ios-card', 1,
        '/manager/liroam/cards*,/manager/other/logistics*', '卡资产台账、交码 / 物流录入、实名核验与导入'),
    (9100000000000000112, 2, 'liroamManualTasks', '人工待办', '9100000000000000110', 'liroam-manual-tasks', 'liroam/manual-tasks/ManualTasks', 'md-alert', 2,
        '/manager/liroam/cards/manual-tasks*', '履约异常人工处理'),

    (9100000000000000120, 1, 'liroam-refund', '退款', '9100000000000000100', '/', 'Main', 'md-list', 2, NULL, 'liroam 退款'),
    (9100000000000000121, 2, 'liroamRefunds', '退款单', '9100000000000000120', 'liroam-refunds', 'liroam/refunds/RefundCases', 'md-list', 1,
        '/manager/liroam/refunds*', '按卡退款审批'),
    (9100000000000000122, 2, 'liroamRefundRules', '退款规则', '9100000000000000120', 'liroam-refund-rules', 'liroam/refunds/RefundRules', 'md-list', 2,
        '/manager/liroam/refund-rules*', '退款时卡状态 → 结论与动作'),

    (9100000000000000130, 1, 'liroam-pricing', '定价', '9100000000000000100', '/', 'Main', 'md-stats', 3, NULL, 'liroam 定价'),
    (9100000000000000131, 2, 'liroamRepriceSuggestions', '调价', '9100000000000000130', 'liroam-reprice-suggestions', 'liroam/pricing/RepriceSuggestions', 'md-stats', 1,
        '/manager/liroam/reprice*', '调价报表、成本或汇率变动生成的调价建议（含批量确认 / 驳回）与批量改价'),
    (9100000000000000132, 2, 'liroamPricingSettings', '定价与汇率', '9100000000000000130', 'liroam-pricing-settings', 'liroam/pricing/PricingSettings', 'md-stats', 2,
        '/manager/liroam/settings/pricing*,/manager/liroam/fx*', '调价开关、阈值与汇率'),
    (9100000000000000133, 2, 'liroamTariffSheets', '资费表与区域', '9100000000000000130', 'liroam-tariff-sheets', 'liroam/pricing/TariffSheets', 'md-stats', 3,
        '/manager/liroam/tariff-sheets*,/manager/liroam/card-products*', '供应商资费表导入与卡产品使用区域'),

    (9100000000000000140, 1, 'liroam-catalog', '商品', '9100000000000000100', '/', 'Main', 'md-pricetag', 0.5, NULL, 'liroam 旅行卡商品'),
    (9100000000000000141, 2, 'liroamTravelCardSkus', '旅行卡商品', '9100000000000000140', 'liroam-travel-card-skus', 'liroam/catalog/TravelCardSkus', 'md-pricetag', 1,
        '/manager/liroam/catalog*,/manager/liroam/sku-bindings*,/manager/liroam/card-products*', '卡产品、套餐模板与映射、SKU 绑定、售价与上下架'),

    (9100000000000000150, 1, 'liroam-ops', '运维', '9100000000000000100', '/', 'Main', 'md-warning', 4, NULL, 'liroam 运维'),
    (9100000000000000151, 2, 'liroamOpsAlerts', '运营告警', '9100000000000000150', 'liroam-ops-alerts', 'liroam/ops/OpsAlerts', 'md-warning', 1,
        '/manager/liroam/ops*', '指令重试、限流、人工积压、交码超时、轮询滞后、回写失败、汇率过期');

SET @liroam_has_menu := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_menu');
SET @liroam_menu_sql := IF(@liroam_has_menu > 0,
    'INSERT INTO li_menu (id, create_by, create_time, delete_flag, update_by, update_time, description, front_route, icon, level, name, parent_id, path, sort_order, title, front_component, permission)
     SELECT s.id, ''liroam'', NOW(), b''0'', ''liroam'', NOW(), s.description, s.front_route, s.icon, s.level, s.name, s.parent_id, s.path, s.sort_order, s.title, NULL, s.permission
     FROM lr_tmp_menu_seed s
     ON DUPLICATE KEY UPDATE delete_flag = b''0'', update_by = ''liroam'', update_time = NOW(), description = s.description, front_route = s.front_route,
         icon = s.icon, level = s.level, name = s.name, parent_id = s.parent_id, path = s.path, sort_order = s.sort_order, title = s.title, permission = s.permission',
    'DO 0');
PREPARE liroam_menu_stmt FROM @liroam_menu_sql;
EXECUTE liroam_menu_stmt;
DEALLOCATE PREPARE liroam_menu_stmt;

DROP TEMPORARY TABLE lr_tmp_menu_seed;
