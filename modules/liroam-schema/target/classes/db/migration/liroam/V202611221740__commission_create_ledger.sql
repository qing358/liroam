-- 佣金与结算（domain-design §4.4）。账本只增：冲正写反向行，不改原行。

CREATE TABLE lr_commission_policy (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                   VARCHAR(64)     NOT NULL,
    mode                   VARCHAR(32)     NOT NULL COMMENT 'PAID_RATE/FLOOR_DIFF/…可扩展',
    scope_type             VARCHAR(16)     NOT NULL COMMENT 'GLOBAL/AGENT_GRADE/SKU',
    scope_ref              VARCHAR(64)     NULL,
    params_json            JSON            NOT NULL,
    include_channel_orders TINYINT(1)      NOT NULL DEFAULT 0,
    settle_days            INT             NOT NULL DEFAULT 1 COMMENT 'T+N',
    settle_anchor          VARCHAR(16)     NOT NULL DEFAULT 'DELIVERED' COMMENT 'DELIVERED/PAID',
    withdraw_approval      TINYINT(1)      NOT NULL DEFAULT 1,
    version                INT             NOT NULL,
    effective_from         DATETIME(3)     NOT NULL,
    created_at             DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at             DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_commission_policy (code, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '佣金策略（版本化，只增）';

CREATE TABLE lr_agent_floor_price (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    policy_id       BIGINT UNSIGNED NOT NULL,
    agent_grade     VARCHAR(32)     NULL,
    agent_ref       VARCHAR(64)     NULL COMMENT 'li_distribution.id',
    sku_binding_id  BIGINT UNSIGNED NOT NULL,
    floor_price_fen BIGINT          NOT NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_floor_price_policy (policy_id, sku_binding_id),
    CONSTRAINT ck_floor_price_target CHECK ((agent_grade IS NULL) <> (agent_ref IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '底价差价模式的代理底价';

CREATE TABLE lr_commission_entry (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_ref         VARCHAR(64)     NOT NULL COMMENT 'li_distribution.id',
    level             TINYINT         NOT NULL COMMENT '1/2',
    source_type       VARCHAR(16)     NOT NULL,
    source_line_ref   VARCHAR(128)    NOT NULL,
    policy_id         BIGINT UNSIGNED NOT NULL,
    policy_version    INT             NOT NULL,
    type              VARCHAR(16)     NOT NULL COMMENT 'ACCRUE/REVERSE/ADJUST/WITHDRAW',
    amount_fen        BIGINT          NOT NULL COMMENT '有符号',
    status            VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SETTLEABLE/SETTLED',
    settleable_at     DATETIME(3)     NULL,
    reverses_entry_id BIGINT UNSIGNED NULL,
    refund_case_id    BIGINT UNSIGNED NULL,
    version           INT             NOT NULL DEFAULT 0,
    created_at        DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at        DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_commission_reverse (refund_case_id, reverses_entry_id),
    KEY idx_commission_agent (agent_ref, status),
    KEY idx_commission_source (source_type, source_line_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '佣金账本（只增）';

CREATE TABLE lr_agent_balance (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_ref      VARCHAR(64)     NOT NULL,
    pending_fen    BIGINT          NOT NULL DEFAULT 0,
    settleable_fen BIGINT          NOT NULL DEFAULT 0 COMMENT '可为负：已提现后冲正',
    withdrawn_fen  BIGINT          NOT NULL DEFAULT 0,
    version        INT             NOT NULL DEFAULT 0,
    created_at     DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at     DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_balance (agent_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '代理余额（账本投影，可重算）';

CREATE TABLE lr_withdrawal (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    agent_ref      VARCHAR(64)     NOT NULL,
    amount_fen     BIGINT          NOT NULL,
    status         VARCHAR(16)     NOT NULL DEFAULT 'APPLIED' COMMENT 'APPLIED/APPROVED/REJECTED/PAID',
    payout_channel VARCHAR(16)     NOT NULL DEFAULT 'MANUAL',
    payout_voucher VARCHAR(255)    NULL,
    approved_by    VARCHAR(64)     NULL,
    approved_at    DATETIME(3)     NULL,
    paid_by        VARCHAR(64)     NULL,
    paid_at        DATETIME(3)     NULL,
    version        INT             NOT NULL DEFAULT 0,
    created_at     DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at     DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_withdrawal_agent (agent_ref, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '提现：无出金通道时审批 + 人工打款登记';
