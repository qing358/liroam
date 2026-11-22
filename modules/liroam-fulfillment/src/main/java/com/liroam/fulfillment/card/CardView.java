package com.liroam.fulfillment.card;

import java.util.List;

/**
 * 买家与后台共用的卡投影（中文文案已就绪，前端不再翻译状态码）。
 *
 * @param refundHold 退款处理中：不能加包、不出激活码
 */
public record CardView(
        long id,
        String cardNo,
        String productName,
        String usageRegionName,
        String form,
        String status,
        String statusText,
        String remainingText,
        String expiresText,
        String updatedText,
        boolean canTopUp,
        boolean canRefund,
        boolean refundHold,
        String iccidLast4,
        String supplierCode,
        List<PlanView> plans) {

    public CardView {
        plans = List.copyOf(plans);
    }

    public record PlanView(long id, String name, String status, String statusText, String remainingText, String expiresText) {
    }
}
