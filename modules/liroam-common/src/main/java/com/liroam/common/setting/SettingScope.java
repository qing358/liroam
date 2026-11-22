package com.liroam.common.setting;

import java.util.Objects;

/** 配置作用域；全局的 {@code id} 固定为 0。 */
public record SettingScope(Type type, long id) {

    public enum Type { GLOBAL, SUPPLIER, SKU, CHANNEL }

    public static final SettingScope GLOBAL = new SettingScope(Type.GLOBAL, 0);

    public SettingScope {
        Objects.requireNonNull(type, "type");
        if (type == Type.GLOBAL && id != 0) {
            throw new IllegalArgumentException("全局作用域 id 必须为 0");
        }
    }

    public static SettingScope supplier(long supplierId) {
        return new SettingScope(Type.SUPPLIER, supplierId);
    }

    public static SettingScope sku(long skuBindingId) {
        return new SettingScope(Type.SKU, skuBindingId);
    }
}
