package com.liroam.common.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class ItDatabaseTest {

    @Test
    void refusesPortsOfOtherProjects() {
        assertThatThrownBy(() -> ItDatabase.checkedPort("3306")).hasMessageContaining("3306");
        assertThatThrownBy(() -> ItDatabase.checkedPort("6379")).hasMessageContaining("6379");
        assertThat(ItDatabase.checkedPort("39306")).isEqualTo(39306);
    }

    @Test
    void connectsToRegisteredPortAndOwnSchemaOnly() throws Exception {
        DataSource ds = ItDatabase.fresh("common");
        try (Connection c = ds.getConnection(); ResultSet rs = c.createStatement().executeQuery("SELECT DATABASE(), @@port")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("liroam_it_common");
            assertThat(c.getMetaData().getURL()).startsWith("jdbc:mysql://127.0.0.1:"
                    + ItDatabase.parseEnv(ItDatabase.repoRoot().resolve("deploy/ports.env")).get("LIROAM_MYSQL_PORT") + "/");
        }
    }
}
