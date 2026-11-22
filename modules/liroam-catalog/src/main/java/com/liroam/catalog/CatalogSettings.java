package com.liroam.catalog;

import com.liroam.common.setting.SettingKey;

/** 可售相关运营配置（domain-design §13）；值存 {@code lr_setting}，这里是唯一注册处。 */
public final class CatalogSettings {

    private CatalogSettings() {
    }

    public static final SettingKey<Boolean> NUMBERED_SKU_ENABLED = SettingKey.global("sell.numbered_sku_enabled", Boolean.class, false,
            "含号码 SKU 可售开关（凭下游合规书面口径开启）");
}
