-- 退款单补充（domain-design §9）：申请原因与人、审批人、申请时按规则定下的卡动作与池动作、金额依据、停卡请求结果。
-- P2 退款从「我的卡」直接发起，source = DIRECT，source_ref = {CARD|PLAN}:{id}:{序号}。

ALTER TABLE lr_refund_case
    ADD COLUMN paid_fen           BIGINT       NOT NULL DEFAULT 0 COMMENT '订单行快照实付（整行，佣金冲正按 refund_fen / paid_fen）；无快照为 0' AFTER refund_fen,
    ADD COLUMN refund_basis       VARCHAR(16)  NOT NULL DEFAULT 'SNAPSHOT' COMMENT 'SNAPSHOT/NO_SNAPSHOT' AFTER paid_fen,
    ADD COLUMN reason             VARCHAR(255) NULL AFTER refund_basis,
    ADD COLUMN applied_by         VARCHAR(64)  NULL COMMENT 'member:{id} / 后台操作人' AFTER reason,
    ADD COLUMN decided_by         VARCHAR(64)  NULL AFTER applied_by,
    ADD COLUMN card_action        VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT '申请时按规则定：NONE/CANCEL/SUSPEND/TERMINATE' AFTER decided_by,
    ADD COLUMN pool_action        VARCHAR(16)  NULL COMMENT '申请时按规则定：RETURN/VOID' AFTER card_action,
    ADD COLUMN reverse_commission TINYINT(1)   NOT NULL DEFAULT 1 AFTER pool_action,
    ADD COLUMN status_request     VARCHAR(24)  NULL COMMENT '批准时停卡请求结果：QUEUED/DEGRADED_TO_SUSPEND/LOCAL_ONLY' AFTER reverse_commission,
    ADD KEY idx_refund_status (status, id);
