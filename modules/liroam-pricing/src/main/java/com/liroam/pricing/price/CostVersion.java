package com.liroam.pricing.price;

/** {@code lr_sku_cost_version} 一行：资费 × 汇率的成本快照，只增不改。 */
public record CostVersion(long id, long skuBindingId, long tariffSheetId, long fxRateVersionId, long costHkdMinor, long costCnyFen,
        String breakdownJson) {
}
