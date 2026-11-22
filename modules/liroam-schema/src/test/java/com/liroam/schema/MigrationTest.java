package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.common.testing.ItDatabase;

class MigrationTest {

    @Test
    void migratesEmptyDatabaseAndSecondRunIsNoop() {
        DataSource ds = ItDatabase.fresh("schema_empty");

        MigrateResult first = LiroamMigrations.migrate(ds);
        MigrateResult second = LiroamMigrations.migrate(ds);

        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(7);
        assertThat(second.migrationsExecuted).isZero();
        assertThat(new JdbcTemplate(ds).queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'lr_fulfillment_line'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void migratesShellDatabaseWithoutTouchingUpstreamTables() {
        DataSource ds = ItDatabase.fresh("schema_shell");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE li_order (id BIGINT PRIMARY KEY, sn VARCHAR(255)) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci");
        jdbc.update("INSERT INTO li_order VALUES (1, 'O1')");

        MigrateResult result = LiroamMigrations.migrate(ds);

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lr_flyway_schema_history WHERE type = 'BASELINE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sn FROM li_order WHERE id = 1", String.class)).isEqualTo("O1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'li_order'", Integer.class))
                .isEqualTo(2);
    }
}
