-- 渠道入单落地（domain-design §11、§12）：回传重试、渠道单上的对账标记、买家认领。只加列。

ALTER TABLE lr_channel_order
    ADD COLUMN last_error        VARCHAR(512) NULL COMMENT '最近一次转人工的原因（未映射 / 不可收单）',
    ADD COLUMN recon_status      VARCHAR(16)  NULL COMMENT '最近一次渠道日对账结果 BALANCED/DIFF；未对账为空',
    ADD COLUMN recon_run_id      BIGINT UNSIGNED NULL,
    ADD COLUMN reconciled_at     DATETIME(3)  NULL,
    ADD COLUMN claimed_member_id VARCHAR(64)  NULL COMMENT '认领的会员（壳 li_member.id），只写一次',
    ADD COLUMN claimed_at        DATETIME(3)  NULL,
    ADD KEY idx_channel_order_external (external_order_id),
    ADD KEY idx_channel_order_ordered (channel_id, ordered_at);

ALTER TABLE lr_channel_order_line
    ADD COLUMN writeback_attempts INT          NOT NULL DEFAULT 0,
    ADD COLUMN writeback_next_at  DATETIME(3)  NULL COMMENT 'READY/FAILED 行下次回传时间',
    ADD COLUMN writeback_error    VARCHAR(512) NULL,
    ADD COLUMN written_back_at    DATETIME(3)  NULL,
    ADD KEY idx_channel_line_writeback (writeback_status, writeback_next_at);
