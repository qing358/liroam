package com.liroam.pricing.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.setting.SettingScope;
import com.liroam.pricing.PricingSettings;
import com.liroam.pricing.testing.PricedCatalog;
import com.liroam.pricing.testing.PricingFixture;

/** 汇率同步（§8.2、CT-PRC-01）。 */
class FxSyncTest {

    private PricingFixture f;
    private long sku;

    @BeforeEach
    void setUp() {
        f = PricingFixture.create("pricing_fx");
        PricedCatalog c = PricedCatalog.seed(f);
        sku = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
    }

    @Test
    void successWritesActiveVersionSupersedesPreviousAndRecomputesCost() {
        f.setFx("0.9");
        FxSyncResult first = f.fxSync.run("system:fx");
        f.setFx("0.95");
        FxSyncResult second = f.fxSync.run("system:fx");

        assertThat(first.ok()).isTrue();
        assertThat(second.ok()).isTrue();
        assertThat(second.sourceKey()).isEqualTo("mock");
        FxRateVersion active = f.fxRates.active().orElseThrow();
        assertThat(active.id()).isEqualTo(second.versionId());
        assertThat(active.rate()).isEqualByComparingTo("0.95");
        assertThat(active.fetchedAt()).isEqualTo(f.clock.instant());
        assertThat(f.jdbc.queryForObject("SELECT status FROM lr_fx_rate_version WHERE id = ?", String.class, first.versionId())).isEqualTo("SUPERSEDED");
        assertThat(f.costVersions.latest(sku).orElseThrow().fxRateVersionId()).isEqualTo(second.versionId());
        assertThat(f.fxRates.recent(10)).extracting(FxRateRow::id).containsExactly(second.versionId(), first.versionId());
    }

    @Test
    void ct_prc_01_failureKeepsPreviousRateAuditsAndDoesNotReprice() {
        f.setFx("0.9");
        long good = f.fxSync.run("system:fx").versionId();
        f.prices.activateManual(sku, 3000, "manager:ops");
        int costVersions = f.count("SELECT COUNT(*) FROM lr_sku_cost_version");
        int writes = f.writer.calls.size();

        f.mockFx.failNext("上游超时");
        FxSyncResult failed = f.fxSync.run("system:fx");

        assertThat(failed.ok()).isFalse();
        assertThat(failed.error()).contains("上游超时");
        assertThat(f.fxRates.active().orElseThrow().id()).isEqualTo(good);
        assertThat(f.count("SELECT COUNT(*) FROM lr_fx_rate_version")).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM lr_sku_cost_version")).isEqualTo(costVersions);
        assertThat(f.count("SELECT COUNT(*) FROM lr_reprice_suggestion")).isZero();
        assertThat(f.prices.active(sku).orElseThrow().priceFen()).isEqualTo(3000);
        assertThat(f.writer.calls).hasSize(writes);
        assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'FX_SYNC_FAILED'")).isEqualTo(1);
    }

    @Test
    void unknownSourceKeyFailsWithoutWriting() {
        f.settings.set(PricingSettings.FX_SOURCE, SettingScope.GLOBAL, "bank_x", "manager:fin");

        FxSyncResult result = f.fxSync.run("system:fx");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("bank_x");
        assertThat(f.fxRates.active()).isEmpty();
    }

    @Test
    void nonPositiveRateIsRejected() {
        f.setFx("0");

        assertThat(f.fxSync.run("system:fx").ok()).isFalse();
        assertThat(f.fxRates.active()).isEmpty();
    }

    @Test
    void staleWhenNeverSyncedOrOlderThanSetting() {
        assertThat(f.fxSync.isStale()).isTrue();

        f.setFx("0.9");
        f.fxSync.run("system:fx");
        assertThat(f.fxSync.isStale()).isFalse();

        Instant synced = f.clock.instant();
        f.clock.set(synced.plus(Duration.ofHours(47)));
        assertThat(f.fxSync.isStale()).isFalse();
        f.clock.set(synced.plus(Duration.ofHours(49)));
        assertThat(f.fxSync.isStale()).isTrue();

        f.settings.set(PricingSettings.FX_STALE_HOURS, SettingScope.GLOBAL, 72, "manager:fin");
        assertThat(f.fxSync.isStale()).isFalse();
    }

    @Test
    void mockSourceQuotesConfiguredRate() {
        f.mockFx.setRate(new BigDecimal("0.9312"));

        FxQuote quote = f.mockFx.fetch("HKD", "CNY");

        assertThat(quote.rate()).isEqualByComparingTo("0.9312");
        assertThat(f.mockFx.key()).isEqualTo("mock");
    }
}
