package com.liroam.refund;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** 退款单展示对象（买家与后台同一份文案）。 */
public record RefundCaseView(long id, long cardAssetId, Long cardPlanId, String status, String statusText, String decision, String decisionText,
        long refundFen, String reason, String createdText) {

    /**
     * 后台退款列表行：退款单平铺 + 属主、卡号、申请时卡状态、规则定下的动作、金额依据（{@code NO_SNAPSHOT} = 无订单快照，金额为 0 需人工核）。
     */
    public record Admin(@JsonUnwrapped RefundCaseView refundCase, String ownerMemberId, String cardNo, String cardStateAtApply, String cardAction,
            String poolAction, boolean reverseCommission, String refundBasis, String appliedBy, String decidedBy) {
    }
}
