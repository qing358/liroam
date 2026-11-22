package com.liroam.pricing.price;

import java.math.BigDecimal;

/**
 * 后台调价建议行。毛利率 = (售价 − 成本) / 售价。
 *
 * @param status PENDING / CONFIRMED / REJECTED / SUPERSEDED
 */
public record RepriceSuggestionRow(long id, long skuBindingId, String liGoodsSkuId, String triggerKind, long currentPriceFen,
        long suggestedPriceFen, BigDecimal marginBefore, BigDecimal marginAfter, String status, String createdText, String decidedBy,
        String decidedText) {
}
