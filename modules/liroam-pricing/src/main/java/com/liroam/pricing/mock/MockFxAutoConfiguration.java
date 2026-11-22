package com.liroam.pricing.mock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.liroam.common.web.WebSurface;

/** 只在 dev/test profile 装配；生产 profile 若误开，{@code @Simulated} 闸让启动失败。 */
@AutoConfiguration
@Profile({"dev", "test"})
public class MockFxAutoConfiguration {

    @Bean
    MockFxRateSource liroamMockFxRateSource() {
        return new MockFxRateSource();
    }

    @Bean
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    MockFxController liroamMockFxController(MockFxRateSource liroamMockFxRateSource) {
        return new MockFxController(liroamMockFxRateSource);
    }
}
