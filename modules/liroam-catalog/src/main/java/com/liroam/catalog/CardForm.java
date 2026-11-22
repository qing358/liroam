package com.liroam.catalog;

import java.util.Locale;

/** 卡形态（{@code lr_sku_binding.form} / {@code lr_card_asset.form}）。实体卡靠物流交付，其余交激活码。 */
public enum CardForm {
    ESIM, PHYSICAL, SOFT;

    public boolean shipsPhysically() {
        return this == PHYSICAL;
    }

    public static CardForm of(String form) {
        if (form == null || form.isBlank()) {
            throw new IllegalArgumentException("卡形态为空");
        }
        return valueOf(form.trim().toUpperCase(Locale.ROOT));
    }

    public static boolean shipsPhysically(String form) {
        return of(form).shipsPhysically();
    }
}
