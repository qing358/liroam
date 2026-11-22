package com.liroam.common.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.liroam.common.testing.TestKeys;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.liroam.common.config.LiroamCommonAutoConfiguration;

class ProductionSafetyGateTest {

    @Simulated
    static class FakeUpstream {
    }

    static class RealUpstream {
    }

    @Configuration
    static class WithSimulated {
        @Bean
        FakeUpstream fakeUpstream() {
            return new FakeUpstream();
        }
    }

    @Configuration
    static class WithRealOnly {
        @Bean
        RealUpstream realUpstream() {
            return new RealUpstream();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class))
            .withPropertyValues(TestKeys.properties());

    @Test
    void prodProfileWithSimulatedBeanFailsStartup() {
        runner.withUserConfiguration(WithSimulated.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("fakeUpstream"));
    }

    @Test
    void stagingCountsAsProduction() {
        runner.withUserConfiguration(WithSimulated.class)
                .withPropertyValues("spring.profiles.active=staging")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void devProfileAllowsSimulatedBean() {
        runner.withUserConfiguration(WithSimulated.class)
                .withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(FakeUpstream.class));
    }

    @Test
    void prodProfileWithRealBeansStarts() {
        runner.withUserConfiguration(WithRealOnly.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionProfileListIsConfigurable() {
        runner.withUserConfiguration(WithSimulated.class)
                .withPropertyValues("spring.profiles.active=live", "liroam.safety.production-profiles=live")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingDataKeysFailStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("LIROAM_DATA_KEY_V1"));
    }
}
