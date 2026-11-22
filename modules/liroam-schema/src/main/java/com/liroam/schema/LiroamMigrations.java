package com.liroam.schema;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 自有表迁移：独立历史表、独立位置，与上游初始化 SQL 互不干扰。
 * 库里已有上游 li_ 表（非空库）时以版本 0 打基线，再执行全部自有迁移。
 */
public final class LiroamMigrations {

    public static final String HISTORY_TABLE = "lr_flyway_schema_history";
    public static final String LOCATION = "classpath:db/migration/liroam";

    private LiroamMigrations() {
    }

    public static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .table(HISTORY_TABLE)
                .locations(LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateMigrationNaming(true)
                .load();
    }

    public static MigrateResult migrate(DataSource dataSource) {
        return flyway(dataSource).migrate();
    }
}
