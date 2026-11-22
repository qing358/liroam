package com.liroam.pricing.price;

/**
 * SKU 当前 ACTIVE 售价版本（人民币分）。
 *
 * @param origin MANUAL / AUTO / SUGGESTION
 */
public record ActivePrice(long skuBindingId, long priceVersionId, long priceFen, long costVersionId, String origin, String activatedBy,
        String activatedText) {
}
