package com.liroam.refund;

import com.liroam.common.setting.SettingKey;

/** 退款相关运营配置（domain-design §13）；这里是唯一注册处。 */
public final class RefundSettings {

    private RefundSettings() {
    }

    public static final SettingKey<Boolean> AUTO_REVIEW = SettingKey.global("refund.auto_review", Boolean.class, false,
            "开启后，规则建议自动通过的退款申请即自动批准并执行卡动作；关闭时建议只供后台参考");
}
