package com.liroam.pricing;

import java.math.BigDecimal;
import java.time.Duration;

import com.liroam.common.setting.SettingKey;
import com.liroam.common.setting.SettingScope;
import com.liroam.pricing.price.RepriceMode;
import com.liroam.pricing.tariff.CarrierRule;

/** 定价相关运营配置（domain-design §13）。 */
public final class PricingSettings {

    private PricingSettings() {
    }

    public static final SettingKey<RepriceMode> REPRICE_MODE = SettingKey.scoped("pricing.reprice_mode", RepriceMode.class,
            RepriceMode.MANUAL_CONFIRM, "成本变化后的调价方式：MANUAL_CONFIRM 只出建议待确认，AUTO 按加价规则直接出售价版本", SettingScope.Type.SKU);

    /** 毛利率变动（百分点，0.03 = 3 个百分点）超过此值才出建议/自动调价。 */
    public static final SettingKey<BigDecimal> REPRICE_THRESHOLD_RATE = SettingKey.scoped("pricing.reprice_threshold_rate", BigDecimal.class,
            new BigDecimal("0.03"), "毛利率变动阈值", SettingScope.Type.SKU);

    /** dev 默认 0.10，待财务给正式初值。 */
    public static final SettingKey<BigDecimal> MIN_MARGIN_RATE = SettingKey.scoped("pricing.min_margin_rate", BigDecimal.class,
            new BigDecimal("0.10"), "最低毛利率；低于即出建议（dev 默认，待财务确认）", SettingScope.Type.SKU);

    public static final SettingKey<String> FX_SOURCE = SettingKey.global("fx.source", String.class, "mock", "汇率来源（FxRateSource.key）");

    /** Spring cron（秒 分 时 日 月 周），按 {@code liroam.shell.zone} 时区解释。 */
    public static final SettingKey<String> FX_CRON = SettingKey.global("fx.cron", String.class, "0 0 9 * * *", "汇率同步时间，默认每日 09:00");

    public static final SettingKey<Integer> FX_STALE_HOURS = SettingKey.global("fx.stale_hours", Integer.class, 48, "汇率超过此小时数未更新即告警");

    public static final SettingKey<CarrierRule> TARIFF_CARRIER_RULE = SettingKey.scoped("tariff.carrier_rule", CarrierRule.class,
            CarrierRule.MAX, "同国多运营商折成一条区域资费的规则", SettingScope.Type.SUPPLIER);

    public static final SettingKey<Duration> ALERT_REPRICE_PENDING_AGE = SettingKey.global("alert.reprice_pending_age", Duration.class,
            Duration.ofHours(24), "调价建议待确认超过此时长即告警");
}
