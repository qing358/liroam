package com.liroam.refund;

/**
 * 佣金冲正端口（domain-design §9.3）：退款完成时在退款单同一事务内调用一次。
 * 实现按 {@code refundFen / paidFen} 比例对该订单行的 ACCRUE 行写 REVERSE 行，幂等键 = (refundCaseId, accrueEntryId)。
 * P5 由佣金模块实现；P2 默认只记审计（{@link AuditOnlyCommissionReversal}）。
 *
 * @param sourceLineRef 履约行的 {@code source_line_ref}（壳 {@code li_order_item.sn} 等）
 * @param paidFen 订单行快照实付（整行）；无快照为 0
 */
public interface CommissionReversal {

    void reverse(long refundCaseId, String sourceLineRef, long refundFen, long paidFen);
}
