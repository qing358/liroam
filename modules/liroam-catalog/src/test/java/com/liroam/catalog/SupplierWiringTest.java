package com.liroam.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.safety.Simulated;
import com.liroam.common.testing.TestKeys;
import com.liroam.schema.LiroamSchemaAutoConfiguration;
import com.liroam.schema.testing.ItSchema;
import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.LookupQuery;
import com.liroam.supplier.spi.LookupResult;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.StatusCommand;
import com.liroam.supplier.spi.StatusResult;
import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.SupplierCapabilities;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.TopUpResult;
import com.liroam.supplier.spi.UsageSnapshot;

/** 启动装配：启用的供应商必须有适配器；生产装了模拟适配器即启动失败（CT-MOCK-01）。 */
class SupplierWiringTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        dataSource = ItSchema.migrated("catalog_wiring");
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM lr_supplier");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamSchemaAutoConfiguration.class,
                        LiroamCatalogAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(TestKeys.properties());
    }

    private static void supplier(String code, String adapterKey, boolean enabled) {
        jdbc.update("INSERT INTO lr_supplier (code, name, adapter_key, enabled) VALUES (?, ?, ?, ?)", code, code, adapterKey, enabled);
    }

    @Test
    void startsWithoutAnySupplierOrAdapter() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SupplierAdapterRegistry.class).find("sup_a")).isEmpty();
        });
    }

    @Test
    void enabledSupplierWithoutAdapterFailsStartup() {
        supplier("SUP_A", "sup_a", true);

        runner().run(ctx -> assertThat(ctx).hasFailed().getFailure()
                .hasMessageContaining("缺少适配器").hasMessageContaining("SUP_A→sup_a"));
    }

    @Test
    void disabledSupplierWithoutAdapterIsTolerated() {
        supplier("SUP_A", "sup_a", false);

        runner().run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void enabledSupplierResolvesAdapterByBeanName() {
        supplier("SUP_A", "sup_a", true);
        SupplierAdapter adapter = mock(SupplierAdapter.class);

        runner().withBean("sup_a", SupplierAdapter.class, () -> adapter).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            Supplier sup = ctx.getBean(SupplierDirectory.class).enabled().getFirst();
            assertThat(ctx.getBean(SupplierAdapterRegistry.class).require(sup)).isSameAs(adapter);
        });
    }

    @Test
    void ct_mock_01_simulatedAdapterFailsStartupInProduction() {
        supplier("SUP_A", "sup_a", true);

        runner().withBean("sup_a", SimulatedAdapter.class, SimulatedAdapter::new)
                .withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasMessageContaining("sup_a"));
    }

    @Test
    void simulatedAdapterIsAllowedOutsideProduction() {
        supplier("SUP_A", "sup_a", true);

        runner().withBean("sup_a", SimulatedAdapter.class, SimulatedAdapter::new)
                .withPropertyValues("spring.profiles.active=dev")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** 只为让 Bean 类型带上 {@code @Simulated}；不模拟任何上游行为。 */
    @Simulated
    static final class SimulatedAdapter implements SupplierAdapter {
        @Override
        public SupplierCapabilities capabilities() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Outcome<ProvisionResult> provision(ProvisionCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Outcome<CredentialResult> fetchCredential(CardRef card) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Outcome<UsageSnapshot> queryUsage(CardRef card) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Outcome<TopUpResult> addPlan(TopUpCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Outcome<StatusResult> changeStatus(StatusCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Outcome<LookupResult> lookup(LookupQuery query) {
            throw new UnsupportedOperationException();
        }
    }
}
