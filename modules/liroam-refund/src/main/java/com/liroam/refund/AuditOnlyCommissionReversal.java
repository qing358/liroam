package com.liroam.refund;

import com.liroam.common.audit.AuditLog;

/** P2 默认冲正：佣金账本尚未落地，只留一条 {@code COMMISSION_REVERSAL_DEFERRED} 审计，P5 按审计补冲正。 */
public class AuditOnlyCommissionReversal implements CommissionReversal {

    private final AuditLog audit;

    public AuditOnlyCommissionReversal(AuditLog audit) {
        this.audit = audit;
    }

    @Override
    public void reverse(long refundCaseId, String sourceLineRef, long refundFen, long paidFen) {
        audit.record("system:refund", "COMMISSION_REVERSAL_DEFERRED", "REFUND_CASE", Long.toString(refundCaseId), null,
                "{\"sourceLineRef\":\"" + sourceLineRef.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"refundFen\":" + refundFen
                        + ",\"paidFen\":" + paidFen + "}");
    }
}
