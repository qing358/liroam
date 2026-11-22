package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.schema.testing.ItSchema;

/** 存储口径体检（domain-design §3）：新迁移只要违反口径，这里就红。 */
class SchemaConventionsTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = new JdbcTemplate(ItSchema.migrated("schema_conventions"));
    }

    private static List<String> columns(String where) {
        return jdbc.queryForList("SELECT CONCAT(table_name, '.', column_name, ' ', column_type) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name <> '" + LiroamMigrations.HISTORY_TABLE + "' AND (" + where + ")", String.class);
    }

    @Test
    void everyOwnTableIsPrefixedLr() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "AND table_name NOT LIKE 'lr\\_%'", String.class)).isEmpty();
    }

    @Test
    void transactionAmountsAreBigintMinorUnits() {
        assertThat(columns("(column_name LIKE '%\\_fen' OR column_name LIKE '%\\_minor') AND column_type <> 'bigint'")).isEmpty();
        assertThat(columns("column_name LIKE '%\\_fen' OR column_name LIKE '%\\_minor'")).hasSizeGreaterThan(15);
        assertThat(columns("(column_name LIKE '%amount%' OR column_name LIKE '%price%') AND column_name NOT IN ('unit_price') "
                + "AND column_name NOT LIKE '%\\_fen' AND column_name NOT LIKE '%\\_minor' AND column_name NOT LIKE '%\\_id'")).isEmpty();
    }

    @Test
    void unitPricesAndRatesAreDecimal() {
        assertThat(columns("column_name IN ('unit_price', 'unit_cost') AND column_type <> 'decimal(18,6)'")).isEmpty();
        assertThat(columns("column_name = 'rate' AND column_type <> 'decimal(18,10)'")).isEmpty();
        assertThat(columns("column_name IN ('unit_price', 'unit_cost', 'rate')")).hasSize(3);
        assertThat(columns("data_type IN ('float', 'double', 'real')")).isEmpty();
        assertThat(columns("data_type = 'decimal' AND column_name NOT IN ('unit_price', 'unit_cost', 'rate') AND column_name NOT LIKE '%rate' "
                + "AND column_name NOT LIKE 'margin\\_%'")).isEmpty();
    }

    @Test
    void timesAreDatetimeMillisWithUtcDefaults() {
        assertThat(columns("data_type = 'timestamp'")).isEmpty();
        assertThat(columns("data_type = 'datetime' AND datetime_precision <> 3")).isEmpty();
        assertThat(columns("data_type = 'datetime' AND column_default IS NOT NULL AND column_default NOT LIKE '%utc_timestamp%'")).isEmpty();
        assertThat(jdbc.queryForList("SELECT t.table_name FROM information_schema.tables t WHERE t.table_schema = DATABASE() "
                + "AND t.table_name <> '" + LiroamMigrations.HISTORY_TABLE + "' AND NOT EXISTS (SELECT 1 FROM information_schema.columns c "
                + "WHERE c.table_schema = t.table_schema AND c.table_name = t.table_name AND c.column_name = 'created_at')", String.class)).isEmpty();
    }

    @Test
    void primaryKeysAreUnsignedAutoIncrementAndShellRefsAreVarchar64() {
        assertThat(columns("column_name = 'id' AND (column_type <> 'bigint unsigned' OR extra NOT LIKE '%auto_increment%')")).isEmpty();
        assertThat(columns("column_name LIKE 'li\\_%' AND column_type <> 'varchar(64)'")).isEmpty();
    }

    @Test
    void sensitiveFieldsAreCipherWithKeyVersionAndUniqueHash() {
        assertThat(columns("column_name LIKE '%\\_cipher' AND data_type <> 'varbinary'")).isEmpty();
        assertThat(columns("column_name LIKE '%\\_hash' AND column_type <> 'binary(32)'")).isEmpty();
        assertThat(jdbc.queryForList("SELECT DISTINCT c.table_name FROM information_schema.columns c WHERE c.table_schema = DATABASE() "
                + "AND c.column_name LIKE '%\\_cipher' AND NOT EXISTS (SELECT 1 FROM information_schema.columns k "
                + "WHERE k.table_schema = c.table_schema AND k.table_name = c.table_name AND k.column_name = 'key_version')", String.class)).isEmpty();
        List<Map<String, Object>> iccidTables = jdbc.queryForList("SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND column_name = 'iccid_cipher'");
        assertThat(iccidTables).hasSize(2);
        for (Map<String, Object> row : iccidTables) {
            String table = (String) row.values().iterator().next();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                    + "AND table_name = ? AND column_name = 'iccid_hash' AND non_unique = 0", Integer.class, table))
                    .as("%s.iccid_hash 唯一", table).isEqualTo(1);
            assertThat(columns("table_name = '" + table + "' AND column_name = 'iccid'")).as("%s 不得有明文 iccid 列", table).isEmpty();
        }
    }
}
