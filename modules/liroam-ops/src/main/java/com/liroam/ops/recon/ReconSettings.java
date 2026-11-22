package com.liroam.ops.recon;

import com.liroam.common.setting.SettingKey;

/** 对账口径配置。 */
public final class ReconSettings {

    public static final SettingKey<String> PAYMENT_METHODS = SettingKey.global("recon.payment_methods", String.class, "WECHAT,ALIPAY",
            "支付日对账覆盖的壳支付方式（逗号分隔）；余额等不经支付机构的订单不进对账");

    public static final SettingKey<Boolean> PAYMENT_DAILY_REQUIRED = SettingKey.global("recon.payment_daily_required", Boolean.class, true,
            "昨天的支付日对账没做时告警");

    private ReconSettings() {
    }
}
