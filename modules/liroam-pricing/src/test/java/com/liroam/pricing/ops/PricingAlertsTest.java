package com.liroam.pricing.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.liroam.common.ops.OpsAlert;
import com.liroam.common.time.UtcColumns;
import com.liroam.pricing.testing.PricingFixture;

/** 定价告警：从未同步汇率即过期；同步后消失；调价建议待确认超过 24 小时才算积压。 */
class PricingAlertsTest {

    @Test
    void fxStaleAndRepriceBacklog() {
        PricingFixture f = PricingFixture.create("pricing_alerts");
        PricingAlerts alerts = new PricingAlerts(f.fxSync, f.client, f.settings);
        Instant now = f.clock.instant();

        assertThat(alerts.check(now)).extracting(OpsAlert::code).containsExactly("FX_STALE");
        f.setFx("0.9");
        f.fxSync.run("system:fx");
        assertThat(alerts.check(now)).isEmpty();

        f.jdbc.update("INSERT INTO lr_reprice_suggestion (sku_binding_id, trigger_kind, new_cost_version_id, current_price_fen, suggested_price_fen, "
                + "created_at) VALUES (1, 'FX', 1, 100, 120, ?)", UtcColumns.toColumn(now.minus(Duration.ofHours(2))));
        assertThat(alerts.check(now)).isEmpty();
        assertThat(alerts.check(now.plus(Duration.ofHours(23)))).extracting(OpsAlert::code, OpsAlert::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("REPRICE_BACKLOG", 1L));
    }
}
