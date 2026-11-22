-- 佣金（domain-design §10）补充：订单行入账判定（行级幂等 + 「无代理 / 无策略 / 排除」也记一行，扫描不再重看），
-- 提现收款信息（无出金通道时运营照此人工打款；加密存，界面只显示掩码）与审批备注。

CREATE TABLE lr_commission_accrual (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_type     VARCHAR(16)     NOT NULL,
    source_line_ref VARCHAR(128)    NOT NULL,
    outcome         VARCHAR(16)     NOT NULL COMMENT 'ACCRUED/NO_AGENT/NO_POLICY/EXCLUDED/ZERO',
    policy_id       BIGINT UNSIGNED NULL,
    policy_version  INT             NULL,
    base_fen        BIGINT          NOT NULL DEFAULT 0 COMMENT '入账基数：快照实付 − 入账前已完成退款；冲正按 退款额 / 基数',
    pool_fen        BIGINT          NOT NULL DEFAULT 0,
    anchor_at       DATETIME(3)     NULL COMMENT '结算锚点时间（交码或支付）',
    created_at      DATETIME(3)     NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (id),
    UNIQUE KEY uk_commission_accrual (source_type, source_line_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '订单行佣金入账判定（每行一次）';

ALTER TABLE lr_withdrawal
    ADD COLUMN payee_cipher  VARBINARY(1024) NULL COMMENT '收款信息密文（方式、户名、账号）' AFTER payout_voucher,
    ADD COLUMN payee_masked  VARCHAR(128)    NULL COMMENT '界面展示用掩码' AFTER payee_cipher,
    ADD COLUMN key_version   SMALLINT        NULL AFTER payee_masked,
    ADD COLUMN decision_note VARCHAR(255)    NULL COMMENT '驳回原因 / 打款备注' AFTER key_version;
