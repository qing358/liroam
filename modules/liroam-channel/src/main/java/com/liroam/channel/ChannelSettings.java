package com.liroam.channel;

import java.time.Duration;

import com.liroam.common.setting.SettingKey;
import com.liroam.common.setting.SettingScope;

/** 渠道配置键（domain-design §13）。启停、拉单节拍、计佣、买家看用量在 {@code lr_channel} 列上；这里只放回传重试参数。 */
public final class ChannelSettings {

    private ChannelSettings() {
    }

    public static final SettingKey<Integer> WRITEBACK_MAX_ATTEMPTS = SettingKey.scoped("channel.writeback_max_attempts", Integer.class, 6,
            "渠道回传最大尝试次数，超过转人工", SettingScope.Type.CHANNEL);

    public static final SettingKey<Duration> WRITEBACK_BACKOFF_BASE = SettingKey.scoped("channel.writeback_backoff_base", Duration.class,
            Duration.ofSeconds(30), "渠道回传失败退避起点（每次翻倍，最长 30 分钟）", SettingScope.Type.CHANNEL);
}
