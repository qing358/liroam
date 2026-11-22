package com.liroam.channel.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.channel.ChannelAdapterRegistry;
import com.liroam.channel.ChannelClaims;
import com.liroam.channel.ChannelOrders;
import com.liroam.channel.ChannelSchedules;
import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.channel.spi.ExternalOrder;
import com.liroam.channel.spi.ExternalOrderRef;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.channel.spi.WriteBack;
import com.liroam.channel.spi.WriteBackOutcome;
import com.liroam.channel.web.BuyerChannelOrderController;
import com.liroam.channel.web.ManagerChannelController;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.TestKeys;
import com.liroam.common.web.CurrentActor;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.pricing.catalog.ShellSkuDirectory;
import com.liroam.pricing.config.LiroamPricingAutoConfiguration;
import com.liroam.pricing.price.ShellPriceWriter;
import com.liroam.schema.testing.ItSchema;

/** 装配：适配器按 Bean 名注册、启用渠道缺适配器即启动失败；接口按 {@code liroam.web.surface} 分两侧；节拍只在 worker 进程。 */
class ChannelWiringTest {

    private static DataSource dataSource;

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamCatalogAutoConfiguration.class,
            LiroamFulfillmentAutoConfiguration.class, LiroamPricingAutoConfiguration.class, LiroamChannelAutoConfiguration.class);

    private static final ChannelAdapter NOOP = new ChannelAdapter() {
        @Override
        public List<ExternalOrder> pull(Instant since) {
            return List.of();
        }

        @Override
        public WriteBackOutcome writeBack(ExternalOrderRef ref, WriteBack writeBack) {
            return new WriteBackOutcome.Ok();
        }

        @Override
        public List<ExternalStatementLine> statement(LocalDate day) {
            return List.of();
        }
    };

    @BeforeAll
    static void db() {
        dataSource = ItSchema.migrated("channel_wiring");
    }

    @AfterEach
    void clean() {
        new JdbcTemplate(dataSource).update("DELETE FROM lr_channel");
    }

    private ApplicationContextRunner plain() {
        return new ApplicationContextRunner().withConfiguration(CONFIGS)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(ShellPriceWriter.class, () -> (sku, fen) -> { })
                .withBean(ShellSkuDirectory.class, () -> sku -> Optional.empty())
                .withPropertyValues(TestKeys.properties());
    }

    private WebApplicationContextRunner web() {
        return new WebApplicationContextRunner().withConfiguration(CONFIGS)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(ShellPriceWriter.class, () -> (sku, fen) -> { })
                .withBean(ShellSkuDirectory.class, () -> sku -> Optional.empty())
                .withBean(CurrentActor.class, () -> mock(CurrentActor.class))
                .withPropertyValues(TestKeys.properties());
    }

    private static void channel(String code, String adapterKey, boolean enabled) {
        new JdbcTemplate(dataSource).update("INSERT INTO lr_channel (code, name, adapter_key, enabled) VALUES (?, ?, ?, ?)", code, code, adapterKey,
                enabled);
    }

    @Test
    void adaptersAreRegisteredByBeanName() {
        plain().withBean("mock-x", ChannelAdapter.class, () -> NOOP).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(ChannelOrders.class).hasSingleBean(ChannelClaims.class);
            assertThat(ctx.getBean(ChannelAdapterRegistry.class).find("mock-x")).containsSame(NOOP);
        });
    }

    @Test
    void enabledChannelWithoutAdapterFailsStartupButDisabledOneDoesNot() {
        channel("CH_OFF", "missing-off", false);
        plain().run(ctx -> assertThat(ctx).hasNotFailed());

        channel("CH_ON", "missing-on", true);
        plain().run(ctx -> assertThat(ctx).hasFailed().getFailure()
                .hasMessageContaining("CH_ON→missing-on").hasMessageNotContaining("CH_OFF"));
        plain().withBean("missing-on", ChannelAdapter.class, () -> NOOP).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void webSurfaceSelectsBuyerOrManagerControllers() {
        web().withPropertyValues("liroam.web.surface=buyer").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(BuyerChannelOrderController.class).doesNotHaveBean(ManagerChannelController.class));
        web().withPropertyValues("liroam.web.surface=manager").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(ManagerChannelController.class).doesNotHaveBean(BuyerChannelOrderController.class));
    }

    @Test
    void schedulesOnlyInWorkerProcess() {
        plain().run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ChannelSchedules.class));
        plain().withPropertyValues("liroam.worker.enabled=true").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ChannelSchedules.class));
    }
}
