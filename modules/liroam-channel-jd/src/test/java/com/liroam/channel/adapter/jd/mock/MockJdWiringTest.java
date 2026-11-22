package com.liroam.channel.adapter.jd.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import com.liroam.channel.adapter.mock.LiroamChannelMockAutoConfiguration;
import com.liroam.channel.adapter.mock.SimulatedChannelController;
import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.TestKeys;
import com.liroam.common.web.CurrentActor;

/** 模拟京东只在 dev/test 装配（Bean 名 = adapter_key {@value MockJdChannelAdapter#ADAPTER_KEY}）；生产 profile 不装配，误配启动失败。 */
class MockJdWiringTest {

    private static final AutoConfigurations CONFIGS = AutoConfigurations.of(LiroamCommonAutoConfiguration.class,
            LiroamChannelMockAutoConfiguration.class, LiroamChannelJdMockAutoConfiguration.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(CONFIGS)
            .withPropertyValues(TestKeys.properties());

    @Test
    void devAndTestProfilesWireTheMockByAdapterKey() {
        for (String profile : new String[] {"dev", "test"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeansOfType(ChannelAdapter.class)).containsOnlyKeys(MockJdChannelAdapter.ADAPTER_KEY);
            });
        }
    }

    @Test
    void noProfileOrProductionProfileWiresNoMock() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ChannelAdapter.class));
        runner.withPropertyValues("spring.profiles.active=prod").run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ChannelAdapter.class)
                .doesNotHaveBean(SimulatedChannelController.class));
    }

    @Test
    void testProfileActivatedAlongsideProductionFailsStartup() {
        runner.withPropertyValues("spring.profiles.active=prod,test").run(ctx -> assertThat(ctx).hasFailed().getFailure()
                .hasMessageContaining("@Simulated").hasMessageContaining(MockJdChannelAdapter.ADAPTER_KEY));
    }

    @Test
    void devConsoleListsTheMockOnManagerSurface() {
        WebApplicationContextRunner web = new WebApplicationContextRunner().withConfiguration(CONFIGS)
                .withBean(CurrentActor.class, () -> mock(CurrentActor.class)).withPropertyValues(TestKeys.properties())
                .withPropertyValues("spring.profiles.active=dev");
        web.withPropertyValues("liroam.web.surface=manager").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SimulatedChannelController.class).channels().result())
                    .containsExactly(new SimulatedChannelController.SimulatedChannelView(MockJdChannelAdapter.ADAPTER_KEY, "模拟京东"));
        });
        web.withPropertyValues("liroam.web.surface=buyer").run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(SimulatedChannelController.class));
    }
}
