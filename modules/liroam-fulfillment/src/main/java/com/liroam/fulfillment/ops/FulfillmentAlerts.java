package com.liroam.fulfillment.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.ops.OpsAlert;
import com.liroam.common.ops.OpsAlert.Level;
import com.liroam.common.ops.OpsAlertSource;
import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.FulfillmentSettings;

/** 履约侧告警：指令转人工/反复失败、限流命中、人工积压、长时间未交码、轮询滞后、壳发货回写失败。阈值见 {@link FulfillmentSettings} 的 {@code alert.*}。 */
public class FulfillmentAlerts implements OpsAlertSource {

    private final JdbcClient jdbc;
    private final Settings settings;

    public FulfillmentAlerts(JdbcClient jdbc, Settings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public List<OpsAlert> check(Instant now) {
        List<OpsAlert> alerts = new ArrayList<>();
        long manualCommands = count("SELECT COUNT(*) FROM lr_supplier_command WHERE status = 'MANUAL'");
        if (manualCommands > 0) {
            alerts.add(new OpsAlert("COMMAND_MANUAL", Level.CRITICAL, "供应商指令转人工", "重试用尽或结果不明的指令，请在人工待办处理", manualCommands));
        }
        int retryAt = settings.get(FulfillmentSettings.ALERT_COMMAND_RETRY_ATTEMPTS);
        long retrying = count("SELECT COUNT(*) FROM lr_supplier_command WHERE status IN ('PENDING', 'LOOKUP_PENDING') AND attempts >= ?", retryAt);
        if (retrying > 0) {
            alerts.add(new OpsAlert("COMMAND_RETRYING", Level.WARN, "供应商指令反复失败", "已重试 " + retryAt + " 次以上仍未成功", retrying));
        }
        long throttled = count("SELECT COUNT(*) FROM lr_supplier_command WHERE error_class = 'THROTTLED' AND updated_at >= ?",
                UtcColumns.toColumn(now.minus(Duration.ofHours(1))));
        if (throttled >= settings.get(FulfillmentSettings.ALERT_RATE_LIMITED_PER_HOUR)) {
            alerts.add(new OpsAlert("RATE_LIMITED", Level.WARN, "供应商限流频繁", "近 1 小时被本地令牌桶推迟的指令", throttled));
        }
        long open = count("SELECT COUNT(*) FROM lr_manual_task WHERE status = 'OPEN'");
        Duration age = settings.get(FulfillmentSettings.ALERT_MANUAL_TASK_AGE);
        long stale = count("SELECT COUNT(*) FROM lr_manual_task WHERE status = 'OPEN' AND created_at < ?", UtcColumns.toColumn(now.minus(age)));
        if (open >= settings.get(FulfillmentSettings.ALERT_MANUAL_BACKLOG) || stale > 0) {
            alerts.add(new OpsAlert("MANUAL_BACKLOG", Level.WARN, "人工待办积压", "未处理 " + open + " 条，其中超过 " + age.toHours() + " 小时 " + stale + " 条", open));
        }
        long noCredential = count("SELECT COUNT(*) FROM lr_manual_task WHERE status = 'OPEN' AND kind = 'CREDENTIAL_TIMEOUT'");
        if (noCredential > 0) {
            alerts.add(new OpsAlert("CREDENTIAL_TIMEOUT", Level.CRITICAL, "卡开通后长时间未交码", "买家已付款但拿不到激活码/物流，请尽快录入", noCredential));
        }
        Duration lag = settings.get(FulfillmentSettings.ALERT_POLL_LAG);
        long lagging = count("SELECT COUNT(*) FROM lr_card_asset WHERE next_poll_at IS NOT NULL AND next_poll_at < ?", UtcColumns.toColumn(now.minus(lag)));
        if (lagging > 0) {
            alerts.add(new OpsAlert("POLL_LAG", Level.WARN, "用量查询滞后", "晚于计划超过 " + lag.toMinutes() + " 分钟的卡", lagging));
        }
        int deliveryAt = settings.get(FulfillmentSettings.ALERT_SHELL_DELIVERY_ATTEMPTS);
        long deliveries = count("SELECT COUNT(*) FROM lr_shell_delivery WHERE status = 'PENDING' AND attempts >= ?", deliveryAt);
        if (deliveries > 0) {
            alerts.add(new OpsAlert("SHELL_DELIVERY_FAILING", Level.WARN, "壳订单发货回写失败", "交码已完成但壳订单未置为已发货", deliveries));
        }
        return alerts;
    }

    private long count(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(Long.class).single();
    }
}
