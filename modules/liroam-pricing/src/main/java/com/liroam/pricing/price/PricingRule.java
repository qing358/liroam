package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 加价规则（{@code lr_pricing_rule}）。作用域越细越优先：SKU → 卡产品 → 供应商 → 全局。
 *
 * @param markupMode    MARGIN_RATE：售价 = 成本 / (1 − 比例)；FIXED_ADD：售价 = 成本 + 固定分
 * @param rounding      为空不处理；TAIL_9：向上取到「元.9」（如 28.00 → 28.90）
 * @param minMarginRate 为空用配置 {@code pricing.min_margin_rate}
 */
record PricingRule(String scopeType, String markupMode, BigDecimal markupRate, Long markupAddFen, String rounding, BigDecimal minMarginRate) {

    long priceFor(long costFen) {
        return switch (markupMode) {
            case "MARGIN_RATE" -> divideByOneMinus(costFen, markupRate, RoundingMode.HALF_UP);
            case "FIXED_ADD" -> costFen + markupAddFen;
            default -> throw new IllegalStateException("加价规则模式未知：" + markupMode);
        };
    }

    long round(long priceFen) {
        if (rounding == null || rounding.isBlank()) {
            return priceFen;
        }
        if ("TAIL_9".equals(rounding)) {
            long tail = priceFen / 100 * 100 + 90;
            return tail >= priceFen ? tail : tail + 100;
        }
        throw new IllegalStateException("加价规则尾数处理未知：" + rounding);
    }

    /** 成本 / (1 − 毛利率)。 */
    static long divideByOneMinus(long costFen, BigDecimal marginRate, RoundingMode mode) {
        return BigDecimal.valueOf(costFen).divide(BigDecimal.ONE.subtract(marginRate), 0, mode).longValueExact();
    }
}
