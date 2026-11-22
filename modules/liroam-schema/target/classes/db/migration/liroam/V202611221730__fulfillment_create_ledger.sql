-- 履约与多卡台账（domain-design §4.3）。卡资产只经唯一履约入口写入；
-- 卡属主只写一次、卡生命周期不换供应商由触发器兜底（任何代码路径、包括后台手工 SQL 都拦）。

CREATE TABLE lr_topup_intent (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id       VARCHAR(64)     NOT NULL,
    card_asset_id   BIGINT UNSIGNED NOT NULL,
    li_goods_sku_id VARCHAR(64)     NOT NULL,
    li_order_sn     VARCHAR(64)     NULL COMMENT '交易落库后回填',
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/BOUND/EXPIRED',
    expires_at      DATETIME(3)     NULL,
    version         INT             NOT NULL DEFAULT 0,
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_topup_intent_member (member_id, status),
    KEY idx_topup_intent_order (li_order_sn)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '加包选卡上下文';

CREATE TABLE lr_fulfillment_line (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_type           VARCHAR(16)     NOT NULL COMMENT 'SHOP/CHANNEL/TOPUP',
    source_ref            VARCHAR(128)    NOT NULL COMMENT '订单号 / 渠道单号',
    source_line_ref       VARCHAR(128)    NOT NULL COMMENT '自营 = li_order_item.sn；渠道 = {channel}:{外部行号}',
    qty                   INT             NOT NULL,
    sku_binding_id        BIGINT UNSIGNED NOT NULL,
    owner_member_id       VARCHAR(64)     NULL COMMENT '渠道单可空',
    channel_order_line_id BIGINT UNSIGNED NULL,
    topup_card_asset_id   BIGINT UNSIGNED NULL COMMENT 'TOPUP 必填：加包挂到这张卡',
    status                VARCHAR(24)     NOT NULL DEFAULT 'RECEIVED',
    attempts              INT             NOT NULL DEFAULT 0,
    last_error            VARCHAR(512)    NULL,
    version               INT             NOT NULL DEFAULT 0,
    created_at            DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at            DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fulfillment_source (source_type, source_line_ref),
    KEY idx_fulfillment_status (status),
    KEY idx_fulfillment_source_ref (source_ref),
    CONSTRAINT ck_fulfillment_qty CHECK (qty > 0),
    CONSTRAINT ck_fulfillment_topup CHECK ((source_type = 'TOPUP') = (topup_card_asset_id IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '履约行：幂等键 (source_type, source_line_ref)';

CREATE TABLE lr_card_asset (
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    card_no                VARCHAR(32)     NULL COMMENT '对外展示号',
    fulfillment_line_id    BIGINT UNSIGNED NOT NULL,
    unit_seq               INT             NOT NULL COMMENT '同一履约行内第几张，1..qty',
    supplier_id            BIGINT UNSIGNED NOT NULL,
    card_product_id        BIGINT UNSIGNED NOT NULL,
    region_id              BIGINT UNSIGNED NULL,
    region_group_id        BIGINT UNSIGNED NULL,
    form                   VARCHAR(16)     NOT NULL,
    owner_member_id        VARCHAR(64)     NULL,
    owner_bound_at         DATETIME(3)     NULL,
    iccid_cipher           VARBINARY(128)  NULL,
    iccid_hash             BINARY(32)      NULL,
    iccid_last4            CHAR(4)         NULL,
    key_version            SMALLINT        NULL,
    upstream_ref           VARCHAR(128)    NULL,
    status                 VARCHAR(16)     NOT NULL DEFAULT 'CREATED',
    exception_from         VARCHAR(16)     NULL,
    kyc_status             VARCHAR(16)     NOT NULL DEFAULT 'NOT_REQUIRED',
    refund_hold            TINYINT(1)      NOT NULL DEFAULT 0,
    poll_tier              VARCHAR(16)     NULL,
    next_poll_at           DATETIME(3)     NULL,
    last_polled_at         DATETIME(3)     NULL,
    refresh_cooldown_until DATETIME(3)     NULL,
    activated_at           DATETIME(3)     NULL,
    expires_at             DATETIME(3)     NULL COMMENT '各 READY/IN_USE 套餐最大到期（冗余）',
    version                INT             NOT NULL DEFAULT 0,
    created_at             DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at             DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_asset_unit (fulfillment_line_id, unit_seq),
    UNIQUE KEY uk_card_asset_iccid (iccid_hash),
    UNIQUE KEY uk_card_asset_no (card_no),
    KEY idx_card_asset_owner (owner_member_id),
    KEY idx_card_asset_poll (status, next_poll_at),
    CONSTRAINT ck_card_asset_region CHECK ((region_id IS NULL) <> (region_group_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '卡资产（一张卡）';

DELIMITER $$

CREATE TRIGGER lr_card_asset_owner_bi BEFORE INSERT ON lr_card_asset FOR EACH ROW
BEGIN
    IF NEW.owner_member_id IS NOT NULL AND NEW.owner_bound_at IS NULL THEN
        SET NEW.owner_bound_at = UTC_TIMESTAMP(3);
    END IF;
END$$

CREATE TRIGGER lr_card_asset_invariants_bu BEFORE UPDATE ON lr_card_asset FOR EACH ROW
BEGIN
    IF OLD.owner_member_id IS NOT NULL AND NOT (NEW.owner_member_id <=> OLD.owner_member_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LR_CARD_OWNER_IMMUTABLE: 卡属主只写一次，不可转号';
    END IF;
    IF NOT (NEW.supplier_id <=> OLD.supplier_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'LR_CARD_SUPPLIER_IMMUTABLE: 卡生命周期内不换供应商';
    END IF;
    IF OLD.owner_member_id IS NULL AND NEW.owner_member_id IS NOT NULL THEN
        SET NEW.owner_bound_at = UTC_TIMESTAMP(3);
    END IF;
END$$

DELIMITER ;

CREATE TABLE lr_card_plan (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    card_asset_id           BIGINT UNSIGNED NOT NULL,
    fulfillment_line_id     BIGINT UNSIGNED NOT NULL,
    kind                    VARCHAR(16)     NOT NULL COMMENT 'BASE/TOP_UP',
    plan_template_id        BIGINT UNSIGNED NOT NULL,
    offering_id             BIGINT UNSIGNED NOT NULL,
    status                  VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    upstream_plan_ref       VARCHAR(128)    NULL,
    quota_bytes             BIGINT          NULL,
    used_bytes              BIGINT          NULL,
    remaining_bytes         BIGINT          NULL,
    voice_quota_seconds     BIGINT          NULL,
    voice_used_seconds      BIGINT          NULL,
    voice_remaining_seconds BIGINT          NULL,
    sms_quota               INT             NULL,
    sms_used                INT             NULL,
    sms_remaining           INT             NULL,
    starts_at               DATETIME(3)     NULL,
    expires_at              DATETIME(3)     NULL,
    starts_at_raw           VARCHAR(64)     NULL,
    expires_at_raw          VARCHAR(64)     NULL,
    raw_json                JSON            NULL,
    mirrored_at             DATETIME(3)     NULL,
    version                 INT             NOT NULL DEFAULT 0,
    created_at              DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at              DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_plan_line (fulfillment_line_id, card_asset_id),
    KEY idx_card_plan_card (card_asset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '卡套餐（首购或加包）';

CREATE TABLE lr_card_pool_item (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    supplier_id       BIGINT UNSIGNED NOT NULL,
    card_product_id   BIGINT UNSIGNED NOT NULL,
    form              VARCHAR(16)     NOT NULL,
    iccid_cipher      VARBINARY(128)  NOT NULL,
    iccid_hash        BINARY(32)      NOT NULL,
    iccid_last4       CHAR(4)         NOT NULL,
    credential_cipher VARBINARY(4096) NULL COMMENT '若随卡号批次提供交码内容',
    key_version       SMALLINT        NOT NULL,
    status            VARCHAR(16)     NOT NULL DEFAULT 'IN_STOCK' COMMENT 'IN_STOCK/RESERVED/ALLOCATED/VOID',
    batch_no          VARCHAR(64)     NULL,
    card_asset_id     BIGINT UNSIGNED NULL,
    version           INT             NOT NULL DEFAULT 0,
    created_at        DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at        DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pool_iccid (iccid_hash),
    KEY idx_pool_allocate (supplier_id, card_product_id, form, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '卡池（池模式供应商），分配用 FOR UPDATE SKIP LOCKED';

CREATE TABLE lr_card_credential (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    card_asset_id     BIGINT UNSIGNED NOT NULL,
    kind              VARCHAR(16)     NOT NULL COMMENT 'LPA/QR/ACTIVATION_CODE/SHIPMENT',
    payload_cipher    VARBINARY(4096) NULL,
    key_version       SMALLINT        NULL,
    channel           VARCHAR(16)     NOT NULL COMMENT 'API/IMPORT/MANUAL',
    logistics_company VARCHAR(64)     NULL,
    logistics_no      VARCHAR(64)     NULL,
    delivered_at      DATETIME(3)     NOT NULL,
    operator_id       VARCHAR(64)     NULL,
    superseded_by     BIGINT UNSIGNED NULL,
    created_at        DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at        DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_credential_card (card_asset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '交码：三通道同一行结构';

CREATE TABLE lr_supplier_command (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_no      VARCHAR(128)    NOT NULL COMMENT '{type}-{card_asset_id}-{card_plan_id|0}-{序号}',
    supplier_id     BIGINT UNSIGNED NOT NULL,
    card_asset_id   BIGINT UNSIGNED NULL,
    card_plan_id    BIGINT UNSIGNED NULL,
    type            VARCHAR(24)     NOT NULL,
    priority        TINYINT         NOT NULL COMMENT 'WRITE=0/ON_DEMAND=1/POLL=2',
    payload_json    JSON            NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    attempts        INT             NOT NULL DEFAULT 0,
    max_attempts    INT             NOT NULL DEFAULT 6,
    next_attempt_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    error_class     VARCHAR(16)     NULL,
    error_code      VARCHAR(64)     NULL,
    error_msg       VARCHAR(512)    NULL,
    upstream_ref    VARCHAR(128)    NULL,
    locked_by       VARCHAR(64)     NULL,
    locked_until    DATETIME(3)     NULL,
    version         INT             NOT NULL DEFAULT 0,
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_command_request_no (request_no),
    KEY idx_command_claim (status, priority, next_attempt_at),
    KEY idx_command_card (card_asset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '供应商指令表：写操作一律经 worker 执行';

CREATE TABLE lr_supplier_call_log (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    command_id        BIGINT UNSIGNED NULL,
    supplier_id       BIGINT UNSIGNED NOT NULL,
    operation         VARCHAR(32)     NOT NULL,
    http_status       SMALLINT        NULL,
    latency_ms        INT             NULL,
    request_redacted  MEDIUMTEXT      NULL,
    response_redacted MEDIUMTEXT      NULL,
    outcome           VARCHAR(16)     NOT NULL,
    created_at        DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_call_log_supplier (supplier_id, created_at),
    KEY idx_call_log_command (command_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '上游调用日志（已脱敏）';

CREATE TABLE lr_usage_daily (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    card_asset_id BIGINT UNSIGNED NOT NULL,
    usage_date    DATE            NOT NULL COMMENT '上游日期原值，不做时区换算',
    region_code   VARCHAR(16)     NOT NULL DEFAULT '',
    data_bytes    BIGINT          NOT NULL,
    raw_json      JSON            NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at    DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_daily (card_asset_id, usage_date, region_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '按日用量';

CREATE TABLE lr_manual_task (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    kind       VARCHAR(32)     NOT NULL COMMENT 'COMMAND_FAILED/UNMAPPED_CHANNEL_SKU/CREDENTIAL_TIMEOUT/REFUND_EXCEPTION/RECON_DIFF',
    ref_type   VARCHAR(32)     NOT NULL,
    ref_id     VARCHAR(64)     NOT NULL,
    status     VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/DONE/IGNORED',
    assignee   VARCHAR(64)     NULL,
    note       TEXT            NULL,
    version    INT             NOT NULL DEFAULT 0,
    created_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_manual_task_status (status, kind),
    KEY idx_manual_task_ref (ref_type, ref_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '统一人工队列';

CREATE TABLE lr_sensitive_access_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operator_type VARCHAR(16)     NOT NULL,
    operator_id   VARCHAR(64)     NOT NULL,
    target_type   VARCHAR(32)     NOT NULL,
    target_id     VARCHAR(64)     NOT NULL,
    field         VARCHAR(32)     NOT NULL,
    reason        VARCHAR(255)    NULL,
    ip            VARCHAR(64)     NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_sensitive_access_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '查看明文留审计';
