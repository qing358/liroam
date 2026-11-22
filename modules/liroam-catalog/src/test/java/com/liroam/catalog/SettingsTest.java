package com.liroam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.SettingKey;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.schema.testing.ItSchema;

class SettingsTest {

    enum Mode { MANUAL_CONFIRM, AUTO }

    static final SettingKey<Mode> MODE = SettingKey.scoped("test.mode", Mode.class, Mode.MANUAL_CONFIRM, "模式", SettingScope.Type.SKU);
    static final SettingKey<BigDecimal> RATE = SettingKey.global("test.rate", BigDecimal.class, new BigDecimal("0.03"), "阈值");
    static final SettingKey<Duration> COOLDOWN = SettingKey.global("test.cooldown", Duration.class, Duration.ofMinutes(5), "冷却");
    static final SettingKey<String> NOTE = SettingKey.global("test.note", String.class, "", "备注");

    private static JdbcTemplate jdbc;
    private static Settings settings;

    @BeforeAll
    static void migrate() {
        var ds = ItSchema.migrated("catalog_settings");
        jdbc = new JdbcTemplate(ds);
        JdbcClient client = JdbcClient.create(ds);
        settings = new Settings(client, new AuditLog(client));
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM lr_setting");
        jdbc.update("DELETE FROM lr_audit_log");
    }

    @Test
    void fallsBackFromScopeToGlobalToDefault() {
        SettingScope sku = SettingScope.sku(9);
        assertThat(settings.get(MODE, sku)).isEqualTo(Mode.MANUAL_CONFIRM);

        settings.set(MODE, SettingScope.GLOBAL, Mode.AUTO, "manager:admin");
        assertThat(settings.get(MODE, sku)).isEqualTo(Mode.AUTO);

        settings.set(MODE, sku, Mode.MANUAL_CONFIRM, "manager:admin");
        assertThat(settings.get(MODE, sku)).isEqualTo(Mode.MANUAL_CONFIRM);
        assertThat(settings.get(MODE, SettingScope.sku(10))).isEqualTo(Mode.AUTO);
        assertThat(settings.get(MODE)).isEqualTo(Mode.AUTO);
    }

    @Test
    void roundTripsEveryScalarTypeAndAuditsEachChange() {
        settings.set(RATE, SettingScope.GLOBAL, new BigDecimal("0.0500"), "manager:admin");
        settings.set(COOLDOWN, SettingScope.GLOBAL, Duration.ofSeconds(90), "manager:admin");
        settings.set(NOTE, SettingScope.GLOBAL, "含\"引号\"与\\斜杠", "manager:admin");
        settings.set(RATE, SettingScope.GLOBAL, new BigDecimal("0.04"), "manager:ops");

        assertThat(settings.get(RATE)).isEqualByComparingTo("0.04");
        assertThat(settings.get(COOLDOWN)).isEqualTo(Duration.ofSeconds(90));
        assertThat(settings.get(NOTE)).isEqualTo("含\"引号\"与\\斜杠");
        assertThat(jdbc.queryForObject("SELECT version FROM lr_setting WHERE setting_key = 'test.rate'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'SETTING_SET'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT actor FROM lr_audit_log WHERE target_id = 'test.rate@GLOBAL:0' ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("manager:ops");
    }

    @Test
    void rejectsScopeTheKeyDoesNotAllow() {
        assertThatThrownBy(() -> settings.set(RATE, SettingScope.sku(1), BigDecimal.ONE, "manager:admin"))
                .hasMessageContaining("不支持作用域");
        assertThat(settings.override(RATE, SettingScope.GLOBAL)).isEmpty();
    }
}
