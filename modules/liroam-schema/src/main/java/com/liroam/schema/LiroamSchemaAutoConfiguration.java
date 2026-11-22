package com.liroam.schema;

import javax.sql.DataSource;

import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** 应用启动时执行自有迁移；{@code liroam.flyway.enabled=false} 可关（如只读副本）。 */
@AutoConfiguration
@ConditionalOnProperty(name = "liroam.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class LiroamSchemaAutoConfiguration {

    public record Migrated(MigrateResult result) {
    }

    @Bean
    Migrated liroamSchemaMigrated(DataSource dataSource) {
        return new Migrated(LiroamMigrations.migrate(dataSource));
    }
}
