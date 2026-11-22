package com.liroam.common.setting;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.audit.AuditLog;

/**
 * 读写运营配置（{@code lr_setting}）。读取顺序：指定作用域覆盖值 → 全局覆盖值 → 代码默认值。
 * 值以 JSON 标量存储（字符串/数字/布尔）；每次修改留一条审计。
 */
public class Settings {

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public Settings(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public <T> T get(SettingKey<T> key) {
        return get(key, SettingScope.GLOBAL);
    }

    public <T> T get(SettingKey<T> key, SettingScope scope) {
        if (scope.type() != SettingScope.Type.GLOBAL && key.scopes().contains(scope.type())) {
            Optional<String> scoped = raw(key, scope);
            if (scoped.isPresent()) {
                return decode(key, scoped.get());
            }
        }
        return raw(key, SettingScope.GLOBAL).map(v -> decode(key, v)).orElse(key.defaultValue());
    }

    /** 当前覆盖值（不含默认值回退），后台展示「是否已改过」用。 */
    public <T> Optional<T> override(SettingKey<T> key, SettingScope scope) {
        return raw(key, scope).map(v -> decode(key, v));
    }

    public <T> void set(SettingKey<T> key, SettingScope scope, T value, String actor) {
        if (!key.scopes().contains(scope.type())) {
            throw new IllegalArgumentException("配置 " + key.key() + " 不支持作用域 " + scope.type());
        }
        if (!key.type().isInstance(value)) {
            throw new IllegalArgumentException("配置 " + key.key() + " 需要 " + key.type().getSimpleName());
        }
        String before = raw(key, scope).orElse(null);
        String after = encode(value);
        jdbc.sql("""
                INSERT INTO lr_setting (setting_key, scope_type, scope_id, value_json, updated_by)
                VALUES (?, ?, ?, CAST(? AS JSON), ?)
                ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), updated_by = VALUES(updated_by),
                                        version = version + 1, updated_at = UTC_TIMESTAMP(3)
                """).params(key.key(), scope.type().name(), scope.id(), after, actor).update();
        audit.record(actor, "SETTING_SET", "SETTING", key.key() + "@" + scope.type() + ":" + scope.id(), before, after);
    }

    private Optional<String> raw(SettingKey<?> key, SettingScope scope) {
        return jdbc.sql("SELECT CAST(value_json AS CHAR) FROM lr_setting WHERE setting_key = ? AND scope_type = ? AND scope_id = ?")
                .params(key.key(), scope.type().name(), scope.id()).query(String.class).optional();
    }

    static String encode(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        String text = value instanceof Enum<?> e ? e.name() : value.toString();
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> T decode(SettingKey<T> key, String json) {
        String v = json.trim();
        Class<T> type = key.type();
        Object value;
        if (type == Boolean.class) {
            value = Boolean.valueOf(v);
        } else if (type == Integer.class) {
            value = Integer.valueOf(v);
        } else if (type == Long.class) {
            value = Long.valueOf(v);
        } else if (type == BigDecimal.class) {
            value = new BigDecimal(v);
        } else {
            if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') {
                throw new IllegalStateException("配置 " + key.key() + " 的存储值不是字符串：" + v);
            }
            String text = v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            if (type == Duration.class) {
                value = Duration.parse(text);
            } else if (type.isEnum()) {
                value = Enum.valueOf((Class) type, text);
            } else {
                value = text;
            }
        }
        return type.cast(value);
    }
}
