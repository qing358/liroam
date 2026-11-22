package com.liroam.commission;

import java.time.Duration;

import com.liroam.common.setting.SettingKey;

/** 佣金相关开关（真实值待客户给，先以配置留口）。 */
public final class CommissionSettings {

    public static final SettingKey<Long> WITHDRAW_MIN_FEN = SettingKey.global("commission.withdraw_min_fen", Long.class, 100L,
            "单笔提现最低金额（分）");

    public static final SettingKey<Long> NEGATIVE_BALANCE_ALERT_FEN = SettingKey.global("commission.negative_balance_alert_fen", Long.class, 0L,
            "代理可提现余额低于此值（分，通常为 0 即「负数」）时告警");

    public static final SettingKey<Duration> WITHDRAW_BACKLOG_AGE = SettingKey.global("commission.withdraw_backlog_age", Duration.class,
            Duration.ofHours(48), "提现申请超过多久未处理（未打款）告警");

    private CommissionSettings() {
    }
}
