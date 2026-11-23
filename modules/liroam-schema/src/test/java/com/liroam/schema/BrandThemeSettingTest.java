package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.common.testing.ItDatabase;

/** 平台主题色（THEME_SETTING）：仍是上游出厂红时换成轻游漫游青；运营选过的主题不动。 */
class BrandThemeSettingTest {

    private static final String UPSTREAM = "{\"mode\":\"system\",\"lightColor\":\"#F5222D\",\"aiderLightColor\":\"#FF4D4F\",\"themeColor\":\"#F5222D\"}";

    @Test
    void replacesUpstreamRed() {
        JdbcTemplate jdbc = settingTable("schema_brand_theme_upstream");
        jdbc.update("INSERT INTO li_setting (id, setting_value) VALUES ('THEME_SETTING', ?)", UPSTREAM);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(field(jdbc, "themeColor")).isEqualTo("#0B7A75");
        assertThat(field(jdbc, "lightColor")).isEqualTo("#E4572E");
        assertThat(field(jdbc, "aiderLightColor")).isEqualTo("#54A29E");
        assertThat(field(jdbc, "mode")).isEqualTo("system");
    }

    @Test
    void keepsOperatorTheme() {
        JdbcTemplate jdbc = settingTable("schema_brand_theme_custom");
        String custom = "{\"mode\":\"custom\",\"lightColor\":\"#F5222D\",\"aiderLightColor\":\"#36CFC9\",\"themeColor\":\"#13C2C2\"}";
        jdbc.update("INSERT INTO li_setting (id, setting_value) VALUES ('THEME_SETTING', ?)", custom);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("SELECT setting_value FROM li_setting WHERE id = 'THEME_SETTING'", String.class)).isEqualTo(custom);
    }

    @Test
    void noThemeRowIsFine() {
        JdbcTemplate jdbc = settingTable("schema_brand_theme_absent");

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_setting", Integer.class)).isZero();
    }

    private static JdbcTemplate settingTable(String module) {
        JdbcTemplate jdbc = new JdbcTemplate(ItDatabase.fresh(module));
        jdbc.execute("""
                CREATE TABLE li_setting (id varchar(255) NOT NULL PRIMARY KEY, create_by varchar(64), create_time datetime(6), delete_flag bit(1),
                  update_by varchar(64), update_time datetime(6), setting_value mediumtext) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin""");
        return jdbc;
    }

    private static String field(JdbcTemplate jdbc, String key) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(setting_value, ?)) FROM li_setting WHERE id = 'THEME_SETTING'",
                String.class, "$." + key);
    }
}
