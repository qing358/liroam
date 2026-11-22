-- 退款与对账（domain-design §4.6、§9、§12）。退款规则默认值（§9.2）随退款模块落地时作为配置初值写入，不在本迁移。

CREATE TABLE lr_refund_case (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source              VARCHAR(16)     NOT NULL COMMENT 'AFTER_SALE/ORDER_CANCEL/CHANNEL',
    source_ref          VARCHAR(128)    NOT NULL COMMENT 'li_after_sale.sn 等',
    target_type         VARCHAR(8)      NOT NULL COMMENT 'CARD/PLAN',
    card_asset_id       BIGINT UNSIGNED NOT NULL,
    card_plan_id        BIGINT UNSIGNED NULL,
    card_state_at_apply VARCHAR(16)     NOT NULL,
    decision            VARCHAR(16)     NOT NULL COMMENT 'AUTO_APPROVE/NEED_APPROVAL/DEFAULT_REJECT',
    rule_version        INT             NOT NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'HELD' COMMENT 'HELD/APPROVED/EXECUTING/DONE/RELEASED',
    refund_fen          BIGINT          NOT NULL,
    version             INT             NOT NULL DEFAULT 0,
    created_at          DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at          DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_source (source, source_ref),
    KEY idx_refund_card (card_asset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '退款单（按单张卡/单个加包）';

CREATE TABLE lr_refund_rule (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    card_state         VARCHAR(16)     NOT NULL,
    target_type        VARCHAR(8)      NOT NULL,
    decision           VARCHAR(16)     NOT NULL,
    card_action        VARCHAR(16)     NOT NULL COMMENT 'NONE/CANCEL/SUSPEND/TERMINATE',
    pool_action        VARCHAR(16)     NULL COMMENT 'RETURN/VOID',
    reverse_commission TINYINT(1)      NOT NULL DEFAULT 1,
    version            INT             NOT NULL,
    created_at         DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at         DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_rule (card_state, target_type, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '退款规则（运营可改，版本化）';

CREATE TABLE lr_recon_run (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    kind         VARCHAR(24)     NOT NULL COMMENT 'PAYMENT_DAILY/SUPPLIER_MONTHLY/CHANNEL_DAILY',
    period       VARCHAR(16)     NOT NULL,
    status       VARCHAR(16)     NOT NULL,
    summary_json JSON            NULL,
    created_at   DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at   DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_recon_run (kind, period)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '对账批次';

CREATE TABLE lr_supplier_statement_line (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id           BIGINT UNSIGNED NOT NULL,
    supplier_id      BIGINT UNSIGNED NOT NULL,
    iccid_hash       BINARY(32)      NULL,
    item             VARCHAR(128)    NOT NULL,
    amount_hkd_minor BIGINT          NOT NULL,
    raw_json         JSON            NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_statement_run (run_id),
    KEY idx_statement_iccid (iccid_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '供应商账单导入行（港币）';

CREATE TABLE lr_recon_diff (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id             BIGINT UNSIGNED NOT NULL,
    diff_kind          VARCHAR(24)     NOT NULL COMMENT 'MISSING_OURS/MISSING_THEIRS/AMOUNT/STATUS',
    our_ref            VARCHAR(128)    NULL,
    their_ref          VARCHAR(128)    NULL,
    currency           CHAR(3)         NOT NULL,
    our_amount_minor   BIGINT          NULL,
    their_amount_minor BIGINT          NULL,
    status             VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED/IGNORED',
    resolution         TEXT            NULL,
    resolver           VARCHAR(64)     NULL,
    version            INT             NOT NULL DEFAULT 0,
    created_at         DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at         DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_recon_diff_run (run_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '对账差异（进人工）';
