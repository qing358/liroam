-- 运营闭环（domain-design §9.3、§13）：到期提醒（站内通知 + 可选短信，按卡与到期时间去重）、下游支付日对账的账单行、
-- 对账批次的作用对象（如供应商日对账的供应商）。只增表、只加列。

CREATE TABLE lr_notification (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    member_id       VARCHAR(64)     NOT NULL COMMENT '壳 li_member.id',
    kind            VARCHAR(32)     NOT NULL COMMENT 'EXPIRY_REMIND',
    card_asset_id   BIGINT UNSIGNED NULL,
    dedupe_key      VARCHAR(128)    NOT NULL COMMENT '同一提醒只发一次，如 EXPIRY:{卡}:{到期}:{提前小时}',
    title           VARCHAR(64)     NOT NULL,
    body            VARCHAR(512)    NOT NULL,
    sms_status      VARCHAR(16)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE/SENT/FAILED',
    read_at         DATETIME(3)     NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_dedupe (dedupe_key),
    KEY idx_notification_member (member_id, read_at),
    KEY idx_notification_card (card_asset_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '会员站内通知（到期提醒等）';

CREATE TABLE lr_payment_statement_line (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id     BIGINT UNSIGNED NOT NULL,
    order_sn   VARCHAR(64)     NOT NULL COMMENT '壳 li_order.sn',
    trade_no   VARCHAR(128)    NULL COMMENT '支付机构流水号',
    amount_fen BIGINT          NOT NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    KEY idx_payment_statement_run (run_id),
    KEY idx_payment_statement_sn (order_sn)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '支付机构日账单导入行（人民币）';

ALTER TABLE lr_recon_run
    ADD COLUMN scope_ref  VARCHAR(64) NULL COMMENT '对账对象：供应商日对账为 lr_supplier.id；支付日对账为空' AFTER period,
    ADD COLUMN created_by VARCHAR(64) NULL AFTER summary_json;
