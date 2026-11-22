package com.liroam.pricing.fx;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.pricing.Json;
import com.liroam.pricing.PricingSettings;
import com.liroam.pricing.price.RepriceTrigger;
import com.liroam.pricing.price.Repricing;

/**
 * 汇率同步（§8.2、CT-PRC-01）：按 {@code fx.source} 取来源拉 HKD→CNY；成功写新 ACTIVE 版本并重算 LISTED SKU 成本，
 * 失败（来源缺失、拉取异常、非正汇率）不写库、沿用上一版本、不调价，只留审计与告警日志。
 */
public class FxSync {

    private static final Log log = LogFactory.getLog(FxSync.class);

    private final TransactionOperations tx;
    private final Settings settings;
    private final AuditLog audit;
    private final Supplier<List<FxRateSource>> sources;
    private final FxRates rates;
    private final Repricing repricing;
    private final Clock clock;

    /** @param sources 每次同步现取，来源 Bean 可后于本类装配 */
    public FxSync(TransactionOperations tx, Settings settings, AuditLog audit, Supplier<List<FxRateSource>> sources, FxRates rates,
            Repricing repricing, Clock clock) {
        this.tx = tx;
        this.settings = settings;
        this.audit = audit;
        this.sources = sources;
        this.rates = rates;
        this.repricing = repricing;
        this.clock = clock;
    }

    public FxSyncResult run(String actor) {
        String key = settings.get(PricingSettings.FX_SOURCE);
        FxRateSource source = sources.get().stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
        if (source == null) {
            return fail(actor, key, "未装配汇率来源「" + key + "」，请检查 fx.source 配置");
        }
        FxQuote quote;
        try {
            quote = source.fetch(FxRates.BASE, FxRates.QUOTE);
        } catch (RuntimeException e) {
            return fail(actor, key, "汇率来源「" + key + "」拉取失败：" + e.getMessage());
        }
        if (quote == null || quote.rate() == null || quote.rate().signum() <= 0) {
            return fail(actor, key, "汇率来源「" + key + "」返回非正汇率：" + (quote == null ? null : quote.rate()));
        }
        Instant now = clock.instant();
        long versionId = tx.execute(status -> {
            long id = rates.activate(quote, key, now);
            audit.record(actor, "FX_SYNC", "FX_RATE_VERSION", String.valueOf(id), null,
                    Json.write(Map.of("rate", quote.rate(), "sourceKey", key)));
            return id;
        });
        repricing.recomputeAll(RepriceTrigger.FX, actor);
        return FxSyncResult.success(versionId, quote.rate(), key);
    }

    private FxSyncResult fail(String actor, String key, String error) {
        log.warn("汇率同步失败，沿用上一版本：" + error);
        audit.record(actor, "FX_SYNC_FAILED", "FX_RATE_VERSION", "-", null, Json.write(Map.of("sourceKey", key, "error", error)));
        return FxSyncResult.failure(key, error);
    }

    /** 从未同步过，或最近一次成功距今超过 {@code fx.stale_hours}。 */
    public boolean isStale() {
        Duration limit = Duration.ofHours(settings.get(PricingSettings.FX_STALE_HOURS));
        return rates.active().map(v -> Duration.between(v.fetchedAt(), clock.instant()).compareTo(limit) > 0).orElse(true);
    }

}
