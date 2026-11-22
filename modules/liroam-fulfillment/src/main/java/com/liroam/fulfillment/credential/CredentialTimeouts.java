package com.liroam.fulfillment.credential;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.CardForm;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.task.ManualTasks;

/** 开通后超过 {@code credential.timeout}（默认 2h，供应商可覆盖）仍未交码 → 人工待办 + 告警；每张卡只提醒一次（domain-design §7.5）。 */
public class CredentialTimeouts {

    private static final Logger log = LoggerFactory.getLogger(CredentialTimeouts.class);

    record Waiting(long id, long supplierId, String form, LocalDateTime provisionedAt) {
    }

    private final JdbcClient jdbc;
    private final Settings settings;
    private final ManualTasks tasks;
    private final Clock clock;

    public CredentialTimeouts(JdbcClient jdbc, Settings settings, ManualTasks tasks, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.tasks = tasks;
        this.clock = clock;
    }

    /** @return 本轮新开的待办数 */
    public int runOnce(int batch) {
        Instant now = clock.instant();
        List<Waiting> waiting = jdbc.sql("""
                SELECT c.id, c.supplier_id, c.form, c.provisioned_at FROM lr_card_asset c
                 WHERE c.status = 'PROVISIONED' AND c.provisioned_at IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM lr_card_credential k WHERE k.card_asset_id = c.id)
                   AND NOT EXISTS (SELECT 1 FROM lr_manual_task t WHERE t.kind = 'CREDENTIAL_TIMEOUT' AND t.ref_type = 'CARD' AND t.ref_id = c.id)
                 ORDER BY c.provisioned_at LIMIT ?
                """).param(batch).query(Waiting.class).list();
        int opened = 0;
        for (Waiting w : waiting) {
            Duration timeout = settings.get(FulfillmentSettings.CREDENTIAL_TIMEOUT, SettingScope.supplier(w.supplierId()));
            if (UtcColumns.fromColumn(w.provisionedAt()).plus(timeout).isAfter(now)) {
                continue;
            }
            tasks.open(ManualTasks.Kind.CREDENTIAL_TIMEOUT, "CARD", w.id(), "开通后超过 " + timeout.toMinutes() + " 分钟未交码，"
                    + (CardForm.shipsPhysically(w.form()) ? "请寄出实体卡并录入物流单号" : "请导入或录入激活码"), now);
            log.warn("卡[{}]开通后超时未交码，已转人工待办", w.id());
            opened++;
        }
        return opened;
    }
}
