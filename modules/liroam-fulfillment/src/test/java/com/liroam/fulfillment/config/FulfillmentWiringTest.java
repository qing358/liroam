package com.liroam.fulfillment.config;

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
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.courier.CardTracking;
import com.liroam.fulfillment.courier.mock.MockCourierAutoConfiguration;
import com.liroam.fulfillment.courier.mock.MockCourierTracking;
import com.liroam.fulfillment.web.BuyerCardController;
import com.liroam.fulfillment.web.ManagerCardController;
import com.liroam.fulfillment.worker.FulfillmentSchedules;
import com.liroam.schema.testing.ItSchema;

/** 装配开关：定时节拍只在 {@code liroam.worker.enabled=true}；买家/后台接口按 {@code liroam.web.surface} 且只在 Servlet Web 进程。 */
class FulfillmentWiringTest {

    private static DataSource dataSource;

    @BeforeAll
    static void db() {
        dataSource = ItSchema.migrated("fulfillment_wiring");
    }

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamCatalogAutoConfiguration.class,
            LiroamFulfillmentAutoConfiguration.class, MockCourierAutoConfiguration.class);

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
    void defaultsHaveDomainBeansButNoSchedulesOrControllers() {
        plain().withPropertyValues("liroam.web.surface=buyer").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(CardQueries.class).hasSingleBean(CardActions.class)
                .doesNotHaveBean(FulfillmentSchedules.class).doesNotHaveBean(BuyerCardController.class).doesNotHaveBean(ManagerCardController.class)
                .hasSingleBean(CardTracking.class).doesNotHaveBean(MockCourierTracking.class));
    }

    @Test
    void mockCourierOnlyUnderDevOrTestProfile() {
        plain().withPropertyValues("spring.profiles.active=test").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(MockCourierTracking.class));
        plain().withPropertyValues("spring.profiles.active=dev").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(MockCourierTracking.class));
        plain().withPropertyValues("spring.profiles.active=prod").run(ctx -> assertThat(ctx).doesNotHaveBean(MockCourierTracking.class));
    }

    @Test
    void workerEnabledAddsSchedules() {
        plain().withPropertyValues("liroam.worker.enabled=true").run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(FulfillmentSchedules.class));
        plain().withPropertyValues("liroam.worker.enabled=false").run(ctx -> assertThat(ctx).doesNotHaveBean(FulfillmentSchedules.class));
    }

    @Test
    void webSurfaceSelectsBuyerOrManagerControllers() {
        web().withPropertyValues("liroam.web.surface=buyer").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(BuyerCardController.class).doesNotHaveBean(ManagerCardController.class));
        web().withPropertyValues("liroam.web.surface=manager").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(ManagerCardController.class).doesNotHaveBean(BuyerCardController.class));
        web().run(ctx -> assertThat(ctx).hasNotFailed()
                .doesNotHaveBean(BuyerCardController.class).doesNotHaveBean(ManagerCardController.class));
    }
}
