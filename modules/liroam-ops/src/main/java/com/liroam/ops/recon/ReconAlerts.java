package com.liroam.ops.recon;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.ops.OpsAlert;
import com.liroam.common.ops.OpsAlert.Level;
import com.liroam.common.ops.OpsAlertSource;
import com.liroam.common.setting.Settings;

/** 对账告警：昨天（结算时区）的支付日对账没做、对账差异未处理。 */
public class ReconAlerts implements OpsAlertSource {

    private final JdbcClient jdbc;
    private final Settings settings;
    private final ZoneId zone;

    public ReconAlerts(JdbcClient jdbc, Settings settings, ZoneId zone) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.zone = zone;
    }

    @Override
    public List<OpsAlert> check(Instant now) {
        List<OpsAlert> alerts = new ArrayList<>();
        String yesterday = now.atZone(zone).toLocalDate().minusDays(1).toString();
        if (settings.get(ReconSettings.PAYMENT_DAILY_REQUIRED) && jdbc.sql("SELECT COUNT(*) FROM lr_recon_run WHERE kind = ? AND period = ?")
                .params(PaymentReconciliation.KIND, yesterday).query(Long.class).single() == 0) {
            alerts.add(new OpsAlert("PAYMENT_RECON_MISSING", Level.WARN, "昨日支付对账未做", "请在「对账」导入 " + yesterday + " 的支付机构账单", 1));
        }
        long open = jdbc.sql("SELECT COUNT(*) FROM lr_recon_diff WHERE status = 'OPEN'").query(Long.class).single();
        if (open > 0) {
            alerts.add(new OpsAlert("RECON_DIFF_OPEN", Level.WARN, "对账差异待处理", "支付或供应商对账的差异，处理后写明原因", open));
        }
        return alerts;
    }
}
