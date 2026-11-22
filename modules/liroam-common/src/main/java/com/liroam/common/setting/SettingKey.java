package com.liroam.common.setting;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 运营配置项的代码注册（domain-design §13）：键、类型、默认值、允许的作用域都在这里定义，{@code lr_setting} 只存覆盖值。
 * 各模块把自己的键声明为常量，后台改值走 {@link Settings#set}。
 *
 * @param type 只支持 String / Boolean / Integer / Long / BigDecimal / Duration / 枚举
 */
public record SettingKey<T>(String key, Class<T> type, T defaultValue, Set<SettingScope.Type> scopes, String description) {

    private static final Set<Class<?>> SCALARS = Set.of(String.class, Boolean.class, Integer.class, Long.class, BigDecimal.class, Duration.class);

    public SettingKey {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (!key.matches("[a-z][a-z0-9_.]*")) {
            throw new IllegalArgumentException("配置键只允许小写字母、数字、点与下划线：" + key);
        }
        if (!type.isEnum() && !SCALARS.contains(type)) {
            throw new IllegalArgumentException("不支持的配置类型 " + type.getName());
        }
        scopes = scopes.isEmpty() ? EnumSet.of(SettingScope.Type.GLOBAL) : EnumSet.copyOf(scopes);
        if (!scopes.contains(SettingScope.Type.GLOBAL)) {
            throw new IllegalArgumentException("配置 " + key + " 必须允许全局作用域");
        }
    }

    public static <T> SettingKey<T> global(String key, Class<T> type, T defaultValue, String description) {
        return new SettingKey<>(key, type, defaultValue, EnumSet.of(SettingScope.Type.GLOBAL), description);
    }

    public static <T> SettingKey<T> scoped(String key, Class<T> type, T defaultValue, String description, SettingScope.Type... extra) {
        EnumSet<SettingScope.Type> all = EnumSet.of(SettingScope.Type.GLOBAL);
        all.addAll(Set.of(extra));
        return new SettingKey<>(key, type, defaultValue, all, description);
    }
}
