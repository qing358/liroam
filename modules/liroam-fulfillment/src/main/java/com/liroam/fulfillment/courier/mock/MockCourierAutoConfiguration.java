package com.liroam.fulfillment.courier.mock;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;

/** 只在 dev/test profile 装配；生产 profile 若误开，{@code @Simulated} 闸让启动失败。 */
@AutoConfiguration(after = LiroamFulfillmentAutoConfiguration.class)
@Profile({"dev", "test"})
public class MockCourierAutoConfiguration {

    @Bean
    MockCourierTracking liroamMockCourierTracking(Clock liroamFulfillmentClock) {
        return new MockCourierTracking(liroamFulfillmentClock);
    }
}
