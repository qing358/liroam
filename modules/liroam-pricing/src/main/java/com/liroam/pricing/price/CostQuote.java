package com.liroam.pricing.price;

/** 按当前 ACTIVE 资费表与汇率算出的 SKU 成本，尚未落版本。 */
public record CostQuote(long skuBindingId, long tariffSheetId, long fxRateVersionId, long costHkdMinor, long costCnyFen, String breakdownJson) {
}
