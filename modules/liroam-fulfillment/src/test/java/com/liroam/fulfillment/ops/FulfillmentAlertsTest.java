package com.liroam.fulfillment.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.ops.OpsAlert;
import com.liroam.common.ops.OpsAlerts;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.fulfillment.testing.FulfillmentHarness;

/** 履约告警按台账现状出：健康时为空；转人工指令、长时间未交码、人工积压、轮询滞后、壳回写失败各出一条，阈值可配。 */
class FulfillmentAlertsTest {

    private static FulfillmentHarness h;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_alerts");
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    private static FulfillmentAlerts alerts() {
        return h.bean(FulfillmentAlerts.class);
    }

    @Test
    void healthyLedgerHasNoAlertsAndAlertsBeanAggregatesThisSource() {
        long card = h.acceptCard("O-1", h.catalog.skuBNew, "m1");
        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        assertThat(alerts().check(h.clock.instant())).isEmpty();
        assertThat(h.bean(OpsAlerts.class).current()).isEmpty();
    }

    @Test
    void stuckLedgerRaisesEachAlertOnce() {
        long card = h.acceptCard("O-2", h.catalog.skuBNew, "m1");
        h.drain();
        h.jdbc.update("UPDATE lr_supplier_command SET status = 'MANUAL' WHERE card_asset_id = ? AND type = 'PROVISION'", card);
        h.jdbc.update("UPDATE lr_shell_delivery SET status = 'PENDING', attempts = 3");
        h.bean(ManualTasks.class).open(ManualTasks.Kind.CREDENTIAL_TIMEOUT, "CARD", card, "测试", h.clock.instant());
        h.clock.advance(Duration.ofHours(25));

        assertThat(alerts().check(h.clock.instant())).extracting(OpsAlert::code, OpsAlert::count).containsExactlyInAnyOrder(
                tuple("COMMAND_MANUAL", 1L), tuple("CREDENTIAL_TIMEOUT", 1L), tuple("MANUAL_BACKLOG", 1L), tuple("POLL_LAG", 1L),
                tuple("SHELL_DELIVERY_FAILING", 1L));

        Settings settings = h.bean(Settings.class);
        settings.set(FulfillmentSettings.ALERT_MANUAL_TASK_AGE, SettingScope.GLOBAL, Duration.ofDays(7), "op1");
        settings.set(FulfillmentSettings.ALERT_POLL_LAG, SettingScope.GLOBAL, Duration.ofDays(7), "op1");
        settings.set(FulfillmentSettings.ALERT_SHELL_DELIVERY_ATTEMPTS, SettingScope.GLOBAL, 5, "op1");
        assertThat(alerts().check(h.clock.instant())).extracting(OpsAlert::code).containsExactlyInAnyOrder("COMMAND_MANUAL", "CREDENTIAL_TIMEOUT");
    }
}
