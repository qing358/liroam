package com.liroam.pricing.mock;

import java.math.BigDecimal;
import java.time.Instant;

import com.liroam.common.safety.Simulated;
import com.liroam.pricing.fx.FxQuote;
import com.liroam.pricing.fx.FxRateSource;

/** 本机/测试汇率来源：报运营设定的值；{@link #failNext} 让下一次拉取失败（演示 CT-PRC-01）。 */
@Simulated
public class MockFxRateSource implements FxRateSource {

    public static final String KEY = "mock";
    static final BigDecimal DEFAULT_RATE = new BigDecimal("0.92");

    private volatile BigDecimal rate = DEFAULT_RATE;
    private volatile String failNext;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public FxQuote fetch(String baseCurrency, String quoteCurrency) {
        String failure = failNext;
        if (failure != null) {
            failNext = null;
            throw new IllegalStateException(failure);
        }
        return new FxQuote(rate, Instant.now(), null);
    }

    public BigDecimal rate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public void failNext(String message) {
        this.failNext = message;
    }
}
