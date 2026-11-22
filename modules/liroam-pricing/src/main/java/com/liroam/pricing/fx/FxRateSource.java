package com.liroam.pricing.fx;

/**
 * 汇率来源 SPI（domain-design §8.2）。真实来源待商务定；本机/测试用 mock。按 {@code fx.source} 配置的 {@link #key()} 选用。
 */
public interface FxRateSource {

    String key();

    /** 失败直接抛异常，由 {@link FxSync} 记失败并沿用上一版本。 */
    FxQuote fetch(String baseCurrency, String quoteCurrency);
}
