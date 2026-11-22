package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批量改价请求。
 *
 * @param value PERCENT：涨跌比例，0.05 = 涨 5%、-0.1 = 降 10%；TARGET_MARGIN：目标毛利率，0.3 = 30%
 */
public record BatchAdjust(List<Long> skuBindingIds, Mode mode, BigDecimal value) {

    public enum Mode {
        /** 在当前售价上按比例涨跌，四舍五入到分。 */
        PERCENT,
        /** 最近成本 / (1 − 目标毛利率)，向上取整到分。 */
        TARGET_MARGIN
    }
}
