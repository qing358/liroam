package com.liroam.pricing.ops;

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
import com.liroam.pricing.PricingSettings;
import com.liroam.pricing.fx.FxSync;

/** 定价侧告警：汇率过期（与 {@link FxSync#isStale} 同一口径）、有调价建议待确认（即时通知）、待确认超时积压。 */
public class PricingAlerts implements OpsAlertSource {

    private final FxSync fx;
    private final JdbcClient jdbc;
    private final Settings settings;

    public PricingAlerts(FxSync fx, JdbcClient jdbc, Settings settings) {
        this.fx = fx;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public List<OpsAlert> check(Instant now) {
        List<OpsAlert> alerts = new ArrayList<>();
        if (fx.isStale()) {
            alerts.add(new OpsAlert("FX_STALE", Level.WARN, "汇率过期", "超过 " + settings.get(PricingSettings.FX_STALE_HOURS)
                    + " 小时未成功同步，成本按旧汇率计算", 1));
        }
        Duration age = settings.get(PricingSettings.ALERT_REPRICE_PENDING_AGE);
        record Pending(long fresh, long stale) {
        }
        Pending pending = jdbc.sql("SELECT COALESCE(SUM(created_at >= ?), 0) AS fresh, COALESCE(SUM(created_at < ?), 0) AS stale "
                + "FROM lr_reprice_suggestion WHERE status = 'PENDING'")
                .params(UtcColumns.toColumn(now.minus(age)), UtcColumns.toColumn(now.minus(age))).query(Pending.class).single();
        if (pending.fresh() > 0) {
            alerts.add(new OpsAlert("REPRICE_PENDING", Level.WARN, "调价建议待确认", "汇率或资费变动后出了新的调价建议，请到「调价建议」确认或驳回",
                    pending.fresh()));
        }
        if (pending.stale() > 0) {
            alerts.add(new OpsAlert("REPRICE_BACKLOG", Level.WARN, "调价建议积压", "超过 " + age.toHours() + " 小时未确认", pending.stale()));
        }
        return alerts;
    }
}
