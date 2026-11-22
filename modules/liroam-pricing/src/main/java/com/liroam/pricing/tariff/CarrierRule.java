package com.liroam.pricing.tariff;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 同一国家多家运营商报价折成一条区域资费。 */
public enum CarrierRule {

    /** 取最贵的一家：买家落地连到哪家都不亏。 */
    MAX,
    AVG;

    BigDecimal fold(List<BigDecimal> prices) {
        return switch (this) {
            case MAX -> prices.stream().max(BigDecimal::compareTo).orElseThrow();
            case AVG -> prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(prices.size()), 6, RoundingMode.HALF_UP);
        };
    }
}
