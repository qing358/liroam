package com.liroam.pricing.fx;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 1 单位基础币 = {@code rate} 报价币。
 *
 * @param publishedAt  来源发布时间，可空
 * @param publishedRaw 来源原串，可空
 */
public record FxQuote(BigDecimal rate, Instant publishedAt, String publishedRaw) {
}
