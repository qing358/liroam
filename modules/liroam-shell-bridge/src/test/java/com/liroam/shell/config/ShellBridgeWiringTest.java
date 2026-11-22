package com.liroam.shell.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.channel.MemberPhones;
import com.liroam.channel.config.LiroamChannelAutoConfiguration;
import com.liroam.commission.AgentDirectory;
import com.liroam.commission.CommissionLedger;
import com.liroam.commission.CommissionSchedules;
import com.liroam.commission.config.LiroamCommissionAutoConfiguration;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.ItDatabase;
import com.liroam.common.testing.TestKeys;
import com.liroam.common.web.CurrentActor;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.fulfillment.courier.CardTracking;
import com.liroam.ops.OpsSchedules;
import com.liroam.ops.config.LiroamOpsAutoConfiguration;
import com.liroam.ops.recon.ShellPayments;
import com.liroam.pricing.config.LiroamPricingAutoConfiguration;
import com.liroam.pricing.catalog.ShellSkuDirectory;
import com.liroam.pricing.price.ShellPriceWriter;
import com.liroam.refund.CommissionReversal;
import com.liroam.refund.config.LiroamRefundAutoConfiguration;
import com.liroam.schema.LiroamSchemaAutoConfiguration;
import com.liroam.shell.distribution.ShellAgentDirectory;
import com.liroam.shell.goods.ShellGoodsSkus;
import com.liroam.shell.goods.ShellSkuPriceWriter;
import com.liroam.shell.logistics.ShellCourierTracking;
import com.liroam.shell.member.ShellMemberPhones;
import com.liroam.shell.order.ShellPaidOrders;
import com.liroam.shell.order.ShellShipping;
import com.liroam.shell.order.OrderPaidFulfillmentListener;
import com.liroam.shell.order.PaidOrderCompensationScanner;
import com.liroam.shell.order.TradeIntoDbListener;
import com.liroam.shell.security.ShellCurrentActor;

import cn.lili.cache.Cache;
import cn.lili.modules.goods.service.GoodsService;
import cn.lili.modules.goods.service.GoodsSkuService;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderPackageService;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.system.service.LogisticsService;

/** 壳进程里的装配：监听器两个 API 进程都有；补偿扫描只在开了 worker 的进程（manager-api）。 */
class ShellBridgeWiringTest {

    private static DataSource dataSource;

    @BeforeAll
    static void db() {
        dataSource = ItDatabase.fresh("shell_wiring");
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamSchemaAutoConfiguration.class,
                        LiroamCatalogAutoConfiguration.class, LiroamFulfillmentAutoConfiguration.class, LiroamPricingAutoConfiguration.class,
                        LiroamRefundAutoConfiguration.class, LiroamCommissionAutoConfiguration.class, LiroamOpsAutoConfiguration.class,
                        LiroamChannelAutoConfiguration.class, LiroamShellBridgeAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(OrderItemService.class, () -> mock(OrderItemService.class))
                .withBean(OrderService.class, () -> mock(OrderService.class))
                .withBean(OrderPackageService.class, () -> mock(OrderPackageService.class))
                .withBean(LogisticsService.class, () -> mock(LogisticsService.class))
                .withBean(GoodsSkuService.class, () -> mock(GoodsSkuService.class))
                .withBean(GoodsService.class, () -> mock(GoodsService.class))
                .withBean(Cache.class, () -> mock(Cache.class))
                .withBean(MemberService.class, () -> mock(MemberService.class))
                .withPropertyValues(TestKeys.properties());
    }

    @Test
    void listenerWithoutWorkerByDefault() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(OrderPaidFulfillmentListener.class).doesNotHaveBean(PaidOrderCompensationScanner.class);
            assertThat(ctx.getBean(LiroamSchemaAutoConfiguration.Migrated.class).result().success).isTrue();
        });
    }

    @Test
    void shellHooksFillThePortsOfOwnModules() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(TradeIntoDbListener.class);
            assertThat(ctx.getBean(ShellOrderDelivery.class)).isInstanceOf(ShellShipping.class);
            assertThat(ctx.getBean(ShellPriceWriter.class)).isInstanceOf(ShellSkuPriceWriter.class);
            assertThat(ctx.getBean(ShellSkuDirectory.class)).isInstanceOf(ShellGoodsSkus.class);
            assertThat(ctx.getBean(CurrentActor.class)).isInstanceOf(ShellCurrentActor.class);
            assertThat(ctx).hasSingleBean(ShellCourierTracking.class);
            assertThat(ctx.getBean(CardTracking.class).sourceKeys()).containsExactly(ShellCourierTracking.KEY);
            assertThat(ctx.getBean(AgentDirectory.class)).isInstanceOf(ShellAgentDirectory.class);
            assertThat(ctx.getBean(CommissionReversal.class)).isInstanceOf(CommissionLedger.class);
            assertThat(ctx.getBean(ShellPayments.class)).isInstanceOf(ShellPaidOrders.class);
            assertThat(ctx.getBean(MemberPhones.class)).isInstanceOf(ShellMemberPhones.class);
        });
    }

    @Test
    void workerEnabledAddsCompensationScanner() {
        runner().withPropertyValues("liroam.worker.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(PaidOrderCompensationScanner.class).hasSingleBean(CommissionSchedules.class)
                        .hasSingleBean(OpsSchedules.class));
    }

    @Test
    void missingDataKeysFailStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
