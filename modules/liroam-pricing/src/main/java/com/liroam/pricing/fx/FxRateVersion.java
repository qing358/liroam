package com.liroam.pricing.fx;

import java.math.BigDecimal;
import java.time.Instant;

/** 当前生效的港币→人民币汇率版本。 */
public record FxRateVersion(long id, BigDecimal rate, String sourceKey, Instant fetchedAt) {
}
