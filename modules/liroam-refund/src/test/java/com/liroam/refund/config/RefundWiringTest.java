package com.liroam.refund.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.TestKeys;
import com.liroam.common.web.CurrentActor;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.refund.AuditOnlyCommissionReversal;
import com.liroam.refund.CommissionReversal;
import com.liroam.refund.RefundCases;
import com.liroam.refund.RefundRules;
import com.liroam.refund.web.BuyerRefundController;
import com.liroam.refund.web.ManagerRefundController;
import com.liroam.schema.testing.ItSchema;

/** 装配：默认冲正只记审计且可被替换；买家/后台退款接口按 {@code liroam.web.surface} 且只在 Servlet Web 进程。 */
class RefundWiringTest {

    private static DataSource dataSource;

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamCatalogAutoConfiguration.class,
            LiroamFulfillmentAutoConfiguration.class, LiroamRefundAutoConfiguration.class);

    @BeforeAll
    static void db() {
        dataSource = ItSchema.migrated("refund_wiring");
    }

    private ApplicationContextRunner plain() {
        return new ApplicationContextRunner().withConfiguration(CONFIGS)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withPropertyValues(TestKeys.properties());
    }

    private WebApplicationContextRunner web() {
        return new WebApplicationContextRunner().withConfiguration(CONFIGS)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(CurrentActor.class, () -> mock(CurrentActor.class))
                .withPropertyValues(TestKeys.properties());
    }

    @Test
    void defaultsHaveDomainBeansAndAuditOnlyReversalButNoControllers() {
        plain().withPropertyValues("liroam.web.surface=buyer").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(RefundRules.class).hasSingleBean(RefundCases.class)
                .getBean(CommissionReversal.class).isInstanceOf(AuditOnlyCommissionReversal.class));
        plain().run(ctx -> assertThat(ctx).doesNotHaveBean(BuyerRefundController.class).doesNotHaveBean(ManagerRefundController.class));
    }

    @Test
    void ownCommissionReversalReplacesDefault() {
        CommissionReversal own = (caseId, lineRef, refundFen, paidFen) -> { };
        plain().withBean(CommissionReversal.class, () -> own).run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(CommissionReversal.class).getBean(CommissionReversal.class).isSameAs(own));
    }

    @Test
    void webSurfaceSelectsBuyerOrManagerControllers() {
        web().withPropertyValues("liroam.web.surface=buyer").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(BuyerRefundController.class).doesNotHaveBean(ManagerRefundController.class));
        web().withPropertyValues("liroam.web.surface=manager").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(ManagerRefundController.class).doesNotHaveBean(BuyerRefundController.class));
        web().run(ctx -> assertThat(ctx).hasNotFailed()
                .doesNotHaveBean(BuyerRefundController.class).doesNotHaveBean(ManagerRefundController.class));
    }
}
