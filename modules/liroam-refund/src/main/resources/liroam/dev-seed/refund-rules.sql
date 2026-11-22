-- dev/test 默认退款规则（domain-design §9.2），只在 dev/test 加载；正式数值 P4 向客户要。可重复执行（已有 v1 不动）。
-- 调用：RefundRules.seedDevDefaults(DataSource)。
-- 已交码卡的「SUSPEND→TERMINATE 按能力降级」由 CardActions.requestStatus(TERMINATE) 统一处理，规则里写 TERMINATE。
-- 池动作 NULL（表中「—」）在收尾时按 VOID 处理：已用过的池卡不再分配。
INSERT IGNORE INTO lr_refund_rule (card_state, target_type, decision, card_action, pool_action, reverse_commission, version) VALUES
    ('CREATED',      'CARD', 'AUTO_APPROVE',   'CANCEL',    'RETURN', 1, 1),
    ('ALLOCATED',    'CARD', 'AUTO_APPROVE',   'CANCEL',    'RETURN', 1, 1),
    ('PROVISIONED',  'CARD', 'AUTO_APPROVE',   'TERMINATE', 'VOID',   1, 1),
    ('DELIVERED',    'CARD', 'NEED_APPROVAL',  'TERMINATE', 'VOID',   1, 1),
    ('IN_USE',       'CARD', 'DEFAULT_REJECT', 'TERMINATE', NULL,     1, 1),
    ('SUSPENDED',    'CARD', 'DEFAULT_REJECT', 'TERMINATE', NULL,     1, 1),
    ('EXPIRED',      'CARD', 'DEFAULT_REJECT', 'NONE',      NULL,     0, 1),
    ('TERMINATED',   'CARD', 'DEFAULT_REJECT', 'NONE',      NULL,     0, 1),
    ('PENDING',      'PLAN', 'AUTO_APPROVE',   'CANCEL',    NULL,     1, 1),
    ('PROVISIONING', 'PLAN', 'AUTO_APPROVE',   'CANCEL',    NULL,     1, 1),
    ('READY',        'PLAN', 'NEED_APPROVAL',  'CANCEL',    NULL,     1, 1),
    ('IN_USE',       'PLAN', 'DEFAULT_REJECT', 'NONE',      NULL,     0, 1),
    ('DEPLETED',     'PLAN', 'DEFAULT_REJECT', 'NONE',      NULL,     0, 1),
    ('EXPIRED',      'PLAN', 'DEFAULT_REJECT', 'NONE',      NULL,     0, 1);
