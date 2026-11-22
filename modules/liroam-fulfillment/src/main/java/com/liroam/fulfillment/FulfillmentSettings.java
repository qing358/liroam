package com.liroam.fulfillment;

import java.math.BigDecimal;
import java.time.Duration;

import com.liroam.common.setting.SettingKey;
import com.liroam.common.setting.SettingScope.Type;

/** 履约相关运营配置（domain-design §13）；值存 {@code lr_setting}，这里是唯一注册处。 */
public final class FulfillmentSettings {

    private FulfillmentSettings() {
    }

    public static final SettingKey<Integer> COMMAND_MAX_ATTEMPTS = SettingKey.scoped("command.max_attempts", Integer.class, 6,
            "指令最大尝试次数，超过转人工", Type.SUPPLIER);
    public static final SettingKey<Integer> COMMAND_BACKOFF_BASE_S = SettingKey.scoped("command.backoff_base_s", Integer.class, 30,
            "指令退避基数（秒），每次 ×2", Type.SUPPLIER);
    public static final SettingKey<Integer> COMMAND_BACKOFF_MAX_S = SettingKey.scoped("command.backoff_max_s", Integer.class, 1800,
            "指令退避上限（秒）", Type.SUPPLIER);

    public static final SettingKey<BigDecimal> POLL_RESERVE_RATIO = SettingKey.scoped("poll.reserve_ratio", BigDecimal.class, new BigDecimal("0.2"),
            "写优先预留：轮询/按需刷新只在令牌余量高于容量×该比例时取令牌", Type.SUPPLIER);
    public static final SettingKey<Duration> POLL_ON_DEMAND_COOLDOWN = SettingKey.global("poll.on_demand_cooldown", Duration.class, Duration.ofMinutes(5),
            "打开卡详情触发按需刷新的冷却时间");
    public static final SettingKey<Duration> POLL_TIER_IN_USE = SettingKey.scoped("poll.tier.in_use", Duration.class, Duration.ofMinutes(30),
            "使用中卡的轮询间隔", Type.SUPPLIER);
    public static final SettingKey<Duration> POLL_TIER_NOT_ACTIVATED = SettingKey.scoped("poll.tier.not_activated", Duration.class, Duration.ofHours(6),
            "已交码未激活卡的轮询间隔", Type.SUPPLIER);
    public static final SettingKey<Duration> POLL_TIER_NEAR_EXPIRY = SettingKey.scoped("poll.tier.near_expiry", Duration.class, Duration.ofMinutes(15),
            "临期卡的轮询间隔", Type.SUPPLIER);
    public static final SettingKey<Duration> POLL_TIER_NEAR_EXPIRY_WINDOW = SettingKey.scoped("poll.tier.near_expiry_window", Duration.class, Duration.ofHours(24),
            "到期前多久算临期", Type.SUPPLIER);
    public static final SettingKey<Duration> POLL_TIER_SUSPENDED = SettingKey.scoped("poll.tier.suspended", Duration.class, Duration.ofHours(24),
            "暂停卡的轮询间隔", Type.SUPPLIER);
    public static final SettingKey<Duration> POLL_TIER_EXPIRED = SettingKey.scoped("poll.tier.expired", Duration.class, Duration.ofDays(7),
            "已到期卡的轮询间隔", Type.SUPPLIER);
    public static final SettingKey<Duration> POLL_TIER_EXPIRED_STOP_AFTER = SettingKey.scoped("poll.tier.expired_stop_after", Duration.class, Duration.ofDays(30),
            "到期多久后停止轮询", Type.SUPPLIER);

    public static final SettingKey<Duration> CREDENTIAL_TIMEOUT = SettingKey.scoped("credential.timeout", Duration.class, Duration.ofHours(2),
            "开通后多久未交码转人工待办", Type.SUPPLIER);
}
