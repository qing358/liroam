package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.liroam.common.testing.ItDatabase;

/** R__liroam_menu：壳有 li_menu 时写入「旅行卡」三级菜单且可重放；没有时跳过（见 MigrationTest 的空库/壳库用例）。 */
class MenuSeedTest {

    private static final String SCRIPT = "db/migration/liroam/R__liroam_menu.sql";
    private static final String OURS = "id BETWEEN 9100000000000000100 AND 9100000000000000199";

    @Test
    void seedsTravelCardMenuTreeAndReapplyRestoresIt() throws Exception {
        DataSource ds = ItDatabase.fresh("schema_menu");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE li_menu (
                  id bigint NOT NULL PRIMARY KEY, create_by varchar(255), create_time datetime, delete_flag bit(1), update_by varchar(255),
                  update_time datetime, description varchar(255), front_route varchar(255), icon varchar(255), level int, name varchar(255),
                  parent_id varchar(255), path varchar(255), sort_order decimal(10,2), title varchar(255), front_component varchar(255),
                  permission varchar(255)) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci""");
        jdbc.update("INSERT INTO li_menu (id, level, name, title, parent_id, sort_order) VALUES (1367040819248234496, 0, 'statistics', '统计', '0', 0.70)");

        LiroamMigrations.migrate(ds);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_menu", Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForList("SELECT level, COUNT(*) c FROM li_menu WHERE " + OURS + " GROUP BY level ORDER BY level"))
                .extracting(r -> ((Number) r.get("c")).intValue()).containsExactly(1, 8, 15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_menu c JOIN li_menu p ON c.parent_id = CAST(p.id AS CHAR) "
                + "WHERE c." + OURS + " AND p.level = c.level - 1", Integer.class)).isEqualTo(23);
        List<Map<String, Object>> leaves = jdbc.queryForList("SELECT name, path, front_route, permission FROM li_menu WHERE " + OURS + " AND level = 2");
        assertThat(leaves).allSatisfy(leaf -> {
            assertThat((String) leaf.get("front_route")).startsWith("liroam/");
            assertThat((String) leaf.get("path")).startsWith("liroam-");
            assertThat((String) leaf.get("permission")).startsWith("/manager/liroam/");
        });
        assertThat(jdbc.queryForObject("SELECT title FROM li_menu WHERE id = 9100000000000000100", String.class)).isEqualTo("旅行卡");
        assertThat(leaves).extracting(r -> r.get("front_route")).contains("liroam/catalog/TravelCardSkus", "liroam/ops/OpsAlerts",
                "liroam/commission/CommissionPolicies", "liroam/commission/CommissionLedger", "liroam/ops/OpsReports", "liroam/ops/Reconciliation");
        assertThat(jdbc.queryForObject("SELECT permission FROM li_menu WHERE name = 'liroamCommissionPolicies'", String.class)).as("底价要选 SKU")
                .contains("/manager/liroam/catalog/skus*");
        assertThat(jdbc.queryForObject("SELECT permission FROM li_menu WHERE name = 'liroamCards'", String.class)).as("录物流要读壳物流公司")
                .contains("/manager/other/logistics*");

        jdbc.update("UPDATE li_menu SET title = '被改', delete_flag = b'1' WHERE id = 9100000000000000111");
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(SCRIPT), StandardCharsets.UTF_8));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_menu", Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT CONCAT(title, '/', delete_flag + 0) FROM li_menu WHERE id = 9100000000000000111", String.class))
                .isEqualTo("卡台账/0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'lr\\_tmp%'",
                Integer.class)).isZero();
    }
}
