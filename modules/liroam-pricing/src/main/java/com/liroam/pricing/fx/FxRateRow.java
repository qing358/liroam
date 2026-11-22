package com.liroam.pricing.fx;

import java.math.BigDecimal;

/** 后台汇率历史行。 */
public record FxRateRow(long id, BigDecimal rate, String sourceKey, String status, String fetchedText, String sourcePublishedRaw) {
}
