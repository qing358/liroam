package com.liroam.pricing.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.ItDatabase;
import com.liroam.common.testing.TestKeys;
import com.liroam.common.web.CurrentActor;
import com.liroam.pricing.fx.FxSync;
import com.liroam.pricing.fx.FxSyncJob;
import com.liroam.pricing.mock.MockFxAutoConfiguration;
import com.liroam.pricing.mock.MockFxController;
import com.liroam.pricing.mock.MockFxRateSource;
import com.liroam.pricing.price.PriceVersions;
import com.liroam.pricing.price.ShellPriceWriter;
import com.liroam.pricing.region.UsageRegionDescriber;
import com.liroam.pricing.snapshot.OrderLineSnapshots;
import com.liroam.pricing.tariff.TariffImports;
import com.liroam.pricing.web.PricingBuyerController;
import com.liroam.pricing.web.PricingManagerController;
import com.liroam.schema.LiroamSchemaAutoConfiguration;

/** 壳进程里的装配：控制器按 liroam.web.surface 挂；汇率定时只在 worker 进程；mock 汇率只在 dev/test。 */
class PricingWiringTest {

    private static DataSource dataSource;

    @BeforeAll
    static void db() {
        dataSource = ItDatabase.fresh("pricing_wiring");
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamSchemaAutoConfiguration.class,
                        LiroamCatalogAutoConfiguration.class, LiroamPricingAutoConfiguration.class, MockFxAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(ShellPriceWriter.class, () -> (sku, price) -> { })
                .withBean(CurrentActor.class, () -> new CurrentActor() {
                    @Override
                    public String requireMemberId() {
                        return "M1";
                    }

                    @Override
                    public String requireOperator() {
                        return "manager:t";
                    }
                })
                .withPropertyValues(TestKeys.properties());
    }

    @Test
    void servicesWithoutControllersWorkerOrMockByDefault() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed()
                    .hasSingleBean(TariffImports.class).hasSingleBean(UsageRegionDescriber.class).hasSingleBean(FxSync.class)
                    .hasSingleBean(PriceVersions.class).hasSingleBean(OrderLineSnapshots.class)
                    .doesNotHaveBean(PricingBuyerController.class).doesNotHaveBean(PricingManagerController.class)
                    .doesNotHaveBean(FxSyncJob.class).doesNotHaveBean(MockFxRateSource.class).doesNotHaveBean(MockFxController.class);
            assertThat(ctx.getBean(FxSync.class).run("system:test").ok()).isFalse();
        });
    }

    @Test
    void managerSurfaceRegistersManagerControllerOnly() {
        runner().withPropertyValues("liroam.web.surface=manager").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(PricingManagerController.class).doesNotHaveBean(PricingBuyerController.class));
    }

    @Test
    void buyerSurfaceRegistersBuyerControllerOnly() {
        runner().withPropertyValues("liroam.web.surface=buyer").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(PricingBuyerController.class).doesNotHaveBean(PricingManagerController.class));
    }

    @Test
    void workerEnabledAddsFxSchedule() {
        runner().withPropertyValues("liroam.worker.enabled=true").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(FxSyncJob.class));
    }

    @Test
    void testProfileAddsMockSourceAndDevEndpointOnManagerOnly() {
        runner().withPropertyValues("spring.profiles.active=test", "liroam.web.surface=manager").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(MockFxRateSource.class).hasSingleBean(MockFxController.class);
            assertThat(ctx.getBean(FxSync.class).run("system:test").ok()).isTrue();
        });
        runner().withPropertyValues("spring.profiles.active=test", "liroam.web.surface=buyer").run(ctx ->
                assertThat(ctx).hasNotFailed().hasSingleBean(MockFxRateSource.class).doesNotHaveBean(MockFxController.class));
    }
}
