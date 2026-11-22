package com.liroam.catalog.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierAdapterRegistry;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.catalog.SupplierWiringCheck;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.schema.LiroamSchemaAutoConfiguration;
import com.liroam.supplier.spi.SupplierAdapter;

@AutoConfiguration(after = LiroamSchemaAutoConfiguration.class)
public class LiroamCatalogAutoConfiguration {

    /** 自有模块共用；名字带前缀，避免与壳或 Boot 自带的 JdbcClient 冲突。 */
    @Bean
    JdbcClient liroamJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    AuditLog liroamAuditLog(JdbcClient liroamJdbcClient) {
        return new AuditLog(liroamJdbcClient);
    }

    @Bean
    Settings liroamSettings(JdbcClient liroamJdbcClient, AuditLog liroamAuditLog) {
        return new Settings(liroamJdbcClient, liroamAuditLog);
    }

    @Bean
    SkuBindings liroamSkuBindings(JdbcClient liroamJdbcClient) {
        return new SkuBindings(liroamJdbcClient);
    }

    @Bean
    SupplierDirectory liroamSupplierDirectory(JdbcClient liroamJdbcClient) {
        return new SupplierDirectory(liroamJdbcClient);
    }

    /** 允许一个适配器都没有（P1 尚未接入供应商）。 */
    @Bean
    SupplierAdapterRegistry liroamSupplierAdapterRegistry(ListableBeanFactory beans) {
        return new SupplierAdapterRegistry(beans.getBeansOfType(SupplierAdapter.class));
    }

    /** 在全部单例（含自有迁移）就绪后核对，此时 lr_supplier 已存在。 */
    @Bean
    SupplierWiringCheck liroamSupplierWiringCheck(SupplierDirectory liroamSupplierDirectory, SupplierAdapterRegistry liroamSupplierAdapterRegistry) {
        return new SupplierWiringCheck(liroamSupplierDirectory, liroamSupplierAdapterRegistry);
    }
}
