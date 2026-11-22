package com.liroam.commission;

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

/** 佣金侧告警：代理可提现为负（已提现后被冲正，需线下追回或等后续佣金抵扣）、提现申请积压。阈值见 {@link CommissionSettings}。 */
public class CommissionAlerts implements OpsAlertSource {

    private final JdbcClient jdbc;
    private final Settings settings;

    public CommissionAlerts(JdbcClient jdbc, Settings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public List<OpsAlert> check(Instant now) {
        List<OpsAlert> alerts = new ArrayList<>();
        long threshold = settings.get(CommissionSettings.NEGATIVE_BALANCE_ALERT_FEN);
        long negative = count("SELECT COUNT(*) FROM lr_agent_balance WHERE settleable_fen < ?", threshold);
        if (negative > 0) {
            alerts.add(new OpsAlert("AGENT_NEGATIVE_BALANCE", Level.WARN, "代理佣金余额为负", "已提现后发生退款冲正，后续佣金会先抵扣；必要时线下追回", negative));
        }
        Duration age = settings.get(CommissionSettings.WITHDRAW_BACKLOG_AGE);
        long stale = count("SELECT COUNT(*) FROM lr_withdrawal WHERE status IN ('APPLIED', 'APPROVED') AND created_at < ?",
                UtcColumns.toColumn(now.minus(age)));
        if (stale > 0) {
            alerts.add(new OpsAlert("WITHDRAWAL_BACKLOG", Level.WARN, "提现申请积压", "超过 " + age.toHours() + " 小时未审批或未登记打款", stale));
        }
        return alerts;
    }

    private long count(String sql, Object... args) {
        return jdbc.sql(sql).params(args).query(Long.class).single();
    }
}
