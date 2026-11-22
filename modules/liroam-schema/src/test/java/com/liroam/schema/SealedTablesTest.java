package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.schema.testing.ItSchema;

/** 密文列登记与真实表结构一致：新加 {@code *_cipher} 列不登记就红（否则轮换密钥时漏重加密）；登记的表都有 {@code key_version}。 */
class SealedTablesTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        jdbc = new JdbcTemplate(ItSchema.migrated("sealed_tables"));
    }

    @Test
    void everyCipherColumnIsRegistered() {
        List<String> actual = jdbc.queryForList("SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND column_name LIKE '%\\_cipher'", String.class);
        List<String> registered = SealedTables.ALL.stream()
                .flatMap(t -> t.columns().stream().map(c -> t.table() + "." + c.column())).toList();

        assertThat(registered).containsExactlyInAnyOrderElementsOf(actual);
    }

    @Test
    void everyRegisteredTableHasKeyVersionAndId() {
        for (SealedTables.SealedTable t : SealedTables.ALL) {
            assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?",
                    String.class, t.table())).as(t.table()).contains("id", SealedTables.KEY_VERSION_COLUMN);
        }
    }
}
