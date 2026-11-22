package com.liroam.common.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liroam.common.ops.OpsAlert.Level;

/** 汇总：严重在前、同级按代码排；来源查挂了变成一条严重告警。 */
class OpsAlertsTest {

    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

    @Test
    void criticalFirstAndFailingSourceBecomesAnAlert() {
        OpsAlertSource ok = now -> List.of(new OpsAlert("B_WARN", Level.WARN, "b", "d", 2), new OpsAlert("A_CRIT", Level.CRITICAL, "a", "d", 1));
        OpsAlertSource broken = now -> {
            throw new IllegalStateException("库连不上");
        };

        List<OpsAlert> alerts = new OpsAlerts(() -> List.of(ok, broken), () -> NOW).current();

        assertThat(alerts).extracting(OpsAlert::code).containsExactly("ALERT_SOURCE_FAILED", "A_CRIT", "B_WARN");
        assertThat(alerts.get(0).detail()).contains("库连不上");
        assertThat(new OpsAlerts(() -> List.of(ok), () -> NOW).logCurrent()).isEqualTo(2);
    }
}
