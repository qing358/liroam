-- 管理端自研菜单（壳表 li_menu，三级：顶部导航 level 0 → 侧栏分组 level 1 → 页面 level 2），挂在底座顶栏下（集成方案 §1.4 目标导航）。
-- 页面 front_route 指向 manager/src/views/ 下的组件路径（不含 .vue）；path 为路由路径，name 为路由名，全局唯一。
-- 固定 id 号段 9100000000000000100–9100000000000000299 归 liroam 菜单专用：上游 li_menu 现有 id 在 1.0e17–2.2e18
-- （雪花 id 与上游 update-sql 手写 id），9.1e18 远离两者。各工作流只在分到的子号段内追加（全量开发计划 §4 K2）：
--   WS1 入口归位与功能开关 100–219 · WS3 供应商 220–229 · WS4 上架向导 230–239 · WS7 运营看板 240–249 · 预留 250–299。
-- 已撤掉的 id 不复用：100（原「旅行卡」顶栏）、120（原「退款」组）、150（原「运维」组）、160（原「佣金」组）。
-- 幂等：固定 id + ON DUPLICATE KEY UPDATE，每次改动本文件 Flyway 重放一次，自研菜单回到本文件口径。
-- 底座行（顶栏改名、组改名、组移位、排序）只在仍是出厂值时改，运营在「菜单管理」改过的不动；出厂值即下面 lr_tmp_menu_shell 的 old_* 列，
-- 还原时按 old_* 写回即可。
-- 超级管理员自动看到全部菜单；不写 li_role_menu，普通角色由运营在「角色管理」里授权（与上游 update-sql 一致，不自动扩大授权）。
-- 撤掉的分组连同其 li_role_menu 授权一并删除；挂到底座分组下的页面，非超管角色要另授该底座分组才看得到。
-- permission 为非超管角色的接口放行前缀（逗号分隔，上游 ManagerTokenGenerate 按此授权）。
-- 库里没有 li_menu（自有迁移的空库/测试库）时整段跳过。

-- 底座顶栏与分组（父节点）：
--   订单 1367039534616805376 · 订单›售后 1367043505771249664
--   商品 1367039950368800768 · 商品›基础资料（原「关联管理」）1367044657296441344
--   运营 1367040599596728320 · 运营›分销管理 1374173575405109248
--   统计 1367040819248234496 · 统计›统计 1367052616634204160
--   财务（原「店铺」）1367048084701315072 · 财务›流水 1372807928452481024（原在「订单」下）
--   设置 1348810750596767744 · 设置›系统设置 1349237129847005184
--   运维（原「日志」）1348810864748945408 · 运维›系统监控 1349237928434098176

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
    -- 订单 › 卡与履约
    (9100000000000000110, 1, 'liroam-fulfillment', '卡与履约', '1367039534616805376', '/', 'Main', 'ios-card', 1, NULL, 'liroam 卡与履约'),
    (9100000000000000111, 2, 'liroamCards', '卡台账', '9100000000000000110', 'liroam-cards', 'liroam/cards/CardLedger', 'ios-card', 1,
        '/manager/liroam/cards*,/manager/other/logistics*', '卡资产台账、交码 / 物流录入、实名核验与导入'),
    (9100000000000000112, 2, 'liroamManualTasks', '人工待办', '9100000000000000110', 'liroam-manual-tasks', 'liroam/manual-tasks/ManualTasks', 'md-alert', 2,
        '/manager/liroam/cards/manual-tasks*', '履约异常人工处理'),

    -- 订单 › 售后（底座分组）
    (9100000000000000121, 2, 'liroamRefunds', '退款单', '1367043505771249664', 'liroam-refunds', 'liroam/refunds/RefundCases', 'md-list', -2,
        '/manager/liroam/refunds*', '按卡退款审批'),
    (9100000000000000122, 2, 'liroamRefundRules', '退款规则', '1367043505771249664', 'liroam-refund-rules', 'liroam/refunds/RefundRules', 'md-list', -1,
        '/manager/liroam/refund-rules*', '退款时卡状态 → 结论与动作'),

    -- 订单 › 外部渠道
    (9100000000000000170, 1, 'liroam-channel', '外部渠道', '1367039534616805376', '/', 'Main', 'md-git-network', 2, NULL, 'liroam 外部渠道'),
    (9100000000000000172, 2, 'liroamChannelOrders', '渠道订单', '9100000000000000170', 'liroam-channel-orders', 'liroam/channels/ChannelOrders', 'md-git-network', 1,
        '/manager/liroam/channels*,/manager/liroam/dev/channel-mock*', '渠道单入单 / 回传 / 对账标记、立即拉单、日对账'),
    (9100000000000000171, 2, 'liroamChannels', '渠道与 SKU 映射', '9100000000000000170', 'liroam-channels', 'liroam/channels/Channels', 'md-git-network', 2,
        '/manager/liroam/channels*', '渠道启停、拉单间隔、计佣、买家看用量与渠道 SKU 映射'),

    -- 商品 › 旅行卡配置
    (9100000000000000140, 1, 'liroam-catalog', '旅行卡配置', '1367039950368800768', '/', 'Main', 'md-pricetag', 0.5, NULL, 'liroam 旅行卡配置'),
    (9100000000000000141, 2, 'liroamTravelCardSkus', '旅行卡商品', '9100000000000000140', 'liroam-travel-card-skus', 'liroam/catalog/TravelCardSkus', 'md-pricetag', 1,
        '/manager/liroam/catalog*,/manager/liroam/sku-bindings*,/manager/liroam/card-products*', '卡产品、套餐模板与映射、SKU 绑定、售价与上下架'),
    (9100000000000000133, 2, 'liroamTariffSheets', '资费表与区域', '9100000000000000140', 'liroam-tariff-sheets', 'liroam/pricing/TariffSheets', 'md-stats', 3,
        '/manager/liroam/tariff-sheets*,/manager/liroam/card-products*', '供应商资费表导入与卡产品使用区域'),

    -- 商品 › 定价
    (9100000000000000130, 1, 'liroam-pricing', '定价', '1367039950368800768', '/', 'Main', 'md-stats', 0.6, NULL, 'liroam 定价'),
    (9100000000000000131, 2, 'liroamRepriceSuggestions', '调价', '9100000000000000130', 'liroam-reprice-suggestions', 'liroam/pricing/RepriceSuggestions', 'md-stats', 1,
        '/manager/liroam/reprice*', '调价报表、成本或汇率变动生成的调价建议（含批量确认 / 驳回）与批量改价'),
    (9100000000000000132, 2, 'liroamPricingSettings', '定价与汇率', '9100000000000000130', 'liroam-pricing-settings', 'liroam/pricing/PricingSettings', 'md-stats', 2,
        '/manager/liroam/settings/pricing*,/manager/liroam/fx*', '调价开关、阈值与汇率'),

    -- 运营 › 分销管理（底座分组）：佣金替代壳的分销商品 / 分销订单 / 分销提现
    (9100000000000000161, 2, 'liroamCommissionPolicies', '佣金策略', '1374173575405109248', 'liroam-commission-policies', 'liroam/commission/CommissionPolicies', 'md-people', 7,
        '/manager/liroam/commission/modes*,/manager/liroam/commission/policies*,/manager/liroam/catalog/skus*', '佣金模式、比例、结算窗口、底价'),
    (9100000000000000162, 2, 'liroamCommissionLedger', '佣金与提现', '1374173575405109248', 'liroam-commission-ledger', 'liroam/commission/CommissionLedger', 'md-people', 8,
        '/manager/liroam/commission/withdrawals*,/manager/liroam/commission/balances*,/manager/liroam/commission/entries*', '提现审批、打款登记、分销员余额、佣金流水'),

    -- 统计 › 统计（底座分组）
    (9100000000000000181, 2, 'liroamOpsReports', '经营毛利', '1367052616634204160', 'liroam-ops-reports', 'liroam/ops/OpsReports', 'md-analytics', 8,
        '/manager/liroam/reports*', '按日订单、实付、退款、成本、佣金、毛利（含渠道订单；壳统计只含本站订单）'),

    -- 财务 › 对账
    (9100000000000000180, 1, 'liroam-report', '对账', '1367048084701315072', '/', 'Main', 'md-analytics', -1, NULL, 'liroam 对账'),
    (9100000000000000182, 2, 'liroamReconciliation', '日对账', '9100000000000000180', 'liroam-reconciliation', 'liroam/ops/Reconciliation', 'md-analytics', 1,
        '/manager/liroam/recon*', '支付渠道与供应商日对账、差异处理'),

    -- 运维 › 系统监控（底座分组）
    (9100000000000000151, 2, 'liroamOpsAlerts', '运营告警', '1349237928434098176', 'liroam-ops-alerts', 'liroam/ops/OpsAlerts', 'md-warning', 0.1,
        '/manager/liroam/ops*', '指令重试、限流、人工积压、交码超时、轮询滞后、回写失败、汇率过期'),
    (9100000000000000152, 2, 'liroamOpsOverview', '运维概览', '1349237928434098176', 'liroam-ops-overview', 'liroam/ops/OpsOverview', 'md-analytics', 0.2,
        '/manager/liroam/ops*', '库容量、各类积压、数据密钥版本与分批重加密');

-- 底座行的改动：old_* 为出厂值，只有当前值仍等于出厂值才改成 new_*（NULL 表示该列不动）。
CREATE TEMPORARY TABLE lr_tmp_menu_shell (
    id         BIGINT         NOT NULL PRIMARY KEY,
    old_title  VARCHAR(255)   NULL,
    new_title  VARCHAR(255)   NULL,
    old_parent VARCHAR(255)   NULL,
    new_parent VARCHAR(255)   NULL,
    old_sort   DECIMAL(10, 2) NULL,
    new_sort   DECIMAL(10, 2) NULL
) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci;

INSERT INTO lr_tmp_menu_shell (id, old_title, new_title, old_parent, new_parent, old_sort, new_sort) VALUES
    (1367048084701315072, '店铺', '财务', NULL, NULL, 0.40, 0.75),
    (1348810864748945408, '日志', '运维', NULL, NULL, NULL, NULL),
    (1367044657296441344, '关联管理', '基础资料', NULL, NULL, NULL, NULL),
    (1372807928452481024, NULL, NULL, '1367039534616805376', '1367048084701315072', 3.00, -2);

SET @liroam_has_menu := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_menu');
SET @liroam_has_role_menu := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'li_role_menu');

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

SET @liroam_menu_sql := IF(@liroam_has_menu > 0,
    'UPDATE li_menu m JOIN lr_tmp_menu_shell s ON m.id = s.id
        SET m.title = IF(s.new_title IS NOT NULL AND m.title = s.old_title, s.new_title, m.title),
            m.parent_id = IF(s.new_parent IS NOT NULL AND m.parent_id = s.old_parent, s.new_parent, m.parent_id),
            m.sort_order = IF(s.new_sort IS NOT NULL AND m.sort_order = s.old_sort, s.new_sort, m.sort_order),
            m.update_by = ''liroam'', m.update_time = NOW()
      WHERE (s.new_title IS NOT NULL AND m.title = s.old_title)
         OR (s.new_parent IS NOT NULL AND m.parent_id = s.old_parent)
         OR (s.new_sort IS NOT NULL AND m.sort_order = s.old_sort)',
    'DO 0');
PREPARE liroam_menu_stmt FROM @liroam_menu_sql;
EXECUTE liroam_menu_stmt;
DEALLOCATE PREPARE liroam_menu_stmt;

SET @liroam_menu_sql := IF(@liroam_has_role_menu > 0,
    'DELETE FROM li_role_menu WHERE menu_id IN (''9100000000000000100'', ''9100000000000000120'', ''9100000000000000150'', ''9100000000000000160'')',
    'DO 0');
PREPARE liroam_menu_stmt FROM @liroam_menu_sql;
EXECUTE liroam_menu_stmt;
DEALLOCATE PREPARE liroam_menu_stmt;

SET @liroam_menu_sql := IF(@liroam_has_menu > 0,
    'DELETE FROM li_menu WHERE id IN (9100000000000000100, 9100000000000000120, 9100000000000000150, 9100000000000000160)',
    'DO 0');
PREPARE liroam_menu_stmt FROM @liroam_menu_sql;
EXECUTE liroam_menu_stmt;
DEALLOCATE PREPARE liroam_menu_stmt;

DROP TEMPORARY TABLE lr_tmp_menu_shell;
DROP TEMPORARY TABLE lr_tmp_menu_seed;
