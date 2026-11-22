package com.liroam.pricing.price;

/** 成本变化后的调价方式（{@code pricing.reprice_mode}）。 */
public enum RepriceMode {
    /** 只出调价建议，运营确认后才出新售价版本（默认）。 */
    MANUAL_CONFIRM,
    /** 按加价规则直接出售价版本并留审计。 */
    AUTO
}
