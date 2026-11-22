package com.liroam.ops.notify;

import com.liroam.common.setting.SettingKey;

/** 提醒开关（domain-design §13）。 */
public final class NotifySettings {

    public static final SettingKey<String> EXPIRY_REMIND_BEFORE = SettingKey.global("notify.expiry_remind_before", String.class, "72h,24h",
            "到期前多久提醒，逗号分隔的小时数（如 72h,24h）；每档每张卡每个到期时间只发一次");

    public static final SettingKey<Boolean> EXPIRY_SMS_ENABLED = SettingKey.global("notify.expiry_sms_enabled", Boolean.class, false,
            "到期提醒是否同时发短信（需进程里有短信端口实现）；站内通知总是发");

    private NotifySettings() {
    }
}
