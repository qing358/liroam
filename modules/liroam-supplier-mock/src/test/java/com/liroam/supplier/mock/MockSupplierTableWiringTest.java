package com.liroam.supplier.mock;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.catalog.SupplierAdapterRegistry;
import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.TestKeys;
import com.liroam.schema.LiroamSchemaAutoConfiguration;
import com.liroam.schema.testing.ItSchema;
import com.liroam.supplier.mock.config.LiroamSupplierMockAutoConfiguration;

/** CT-MOCK-01（连本项目测试库）：启用的供应商指向 mock 时，生产 profile 因缺适配器启动失败；dev 下经注册表取到 mock。 */
class MockSupplierTableWiringTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        dataSource = ItSchema.migrated("supplier_mock_wiring");
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM lr_supplier");
        jdbc.update("INSERT INTO lr_supplier (code, name, adapter_key, enabled) VALUES ('SUP_A', 'A', ?, 1)", MockShapeAAdapter.ADAPTER_KEY);
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamSchemaAutoConfiguration.class,
                        LiroamCatalogAutoConfiguration.class, LiroamSupplierMockAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(TestKeys.properties());
    }

    @Test
    void ct_mock_01_productionWithSupplierPointingAtMockFailsStartup() {
        for (String profile : new String[] {"prod", "staging"}) {
            runner().withPropertyValues("spring.profiles.active=" + profile).run(ctx -> assertThat(ctx).hasFailed().getFailure()
                    .hasMessageContaining("缺少适配器").hasMessageContaining("SUP_A→" + MockShapeAAdapter.ADAPTER_KEY));
        }
        runner().run(ctx -> assertThat(ctx).as("未激活任何 profile 同样不装配 mock").hasFailed());
    }

    @Test
    void devResolvesTheMockThroughTheRegistry() {
        runner().withPropertyValues("spring.profiles.active=dev").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SupplierAdapterRegistry.class).find(MockShapeAAdapter.ADAPTER_KEY)).containsInstanceOf(MockShapeAAdapter.class);
        });
    }
}
