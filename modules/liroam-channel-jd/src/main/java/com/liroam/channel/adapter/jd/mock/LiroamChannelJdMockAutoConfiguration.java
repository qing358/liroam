package com.liroam.channel.adapter.jd.mock;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.liroam.common.safety.Simulated;
import com.liroam.common.web.WebSurface;

/**
 * 模拟京东只在 dev/test profile 装配；Bean 名即 {@code lr_channel.adapter_key}。
 * 误把 test 与生产 profile 同时激活时由 {@code ProductionSafetyGate} 按 {@link Simulated} 拦截启动。
 */
@AutoConfiguration(beforeName = "com.liroam.channel.config.LiroamChannelAutoConfiguration")
@Profile({"dev", "test"})
public class LiroamChannelJdMockAutoConfiguration {

    @Bean(MockJdChannelAdapter.ADAPTER_KEY)
    @Simulated
    MockJdChannelAdapter mockJd() {
        return new MockJdChannelAdapter(Clock.systemUTC());
    }

    @Bean
    @Simulated
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    MockJdController liroamMockJdController(MockJdChannelAdapter mockJd) {
        return new MockJdController(mockJd);
    }
}
