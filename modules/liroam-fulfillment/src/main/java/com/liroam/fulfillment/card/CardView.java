package com.liroam.fulfillment.card;

import java.util.List;

/**
 * 买家与后台共用的卡投影（中文文案已就绪，前端不再翻译状态码）。
 *
 * @param refundHold 退款处理中：不能加包、不出激活码
 * @param capabilityText 卡产品能力，如「流量 · 语音 · 短信」
 * @param voiceRemainingText 卡产品无语音能力时为 null
 * @param smsRemainingText 卡产品无短信能力时为 null
 * @param kycBlocking 实名闸拦着交付（BLOCK_DELIVERY 且尚未自报/核验）
 * @param canDeclareKyc 买家可提交「已按要求实名」
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
        String capabilityText,
        String voiceRemainingText,
        String smsRemainingText,
        String kycStatus,
        String kycText,
        boolean kycBlocking,
        boolean canDeclareKyc,
        List<PlanView> plans) {

    public CardView {
        plans = List.copyOf(plans);
    }

    public record PlanView(long id, String name, String status, String statusText, String remainingText, String voiceRemainingText,
            String smsRemainingText, String expiresText) {
    }
}
