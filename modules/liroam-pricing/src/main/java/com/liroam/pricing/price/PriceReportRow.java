package com.liroam.pricing.price;

import java.math.BigDecimal;

/**
 * 调价报表一行（一个在售 SKU）。毛利率 = (售价 − 最近成本) / 售价；没有售价或成本时为空，也不算低于最低毛利。
 *
 * @param regionText          单区域为区域名，区域组为组名
 * @param pendingSuggestionId 待确认的调价建议，没有为空
 */
public record PriceReportRow(long skuBindingId, String liGoodsSkuId, String supplierCode, String cardProduct, String regionText, String form,
        String planName, Long priceFen, Long costCnyFen, BigDecimal marginRate, BigDecimal minMarginRate, boolean belowMin,
        Long pendingSuggestionId, Long suggestedPriceFen) {
}
