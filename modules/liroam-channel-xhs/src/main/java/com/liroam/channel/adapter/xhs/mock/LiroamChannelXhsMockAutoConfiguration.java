package com.liroam.channel.adapter.xhs.mock;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.liroam.common.safety.Simulated;

/**
 * 模拟小红书只在 dev/test profile 装配；Bean 名即 {@code lr_channel.adapter_key}。dev 控制台由 {@code liroam-channel-mock} 通用提供。
 * 误把 test 与生产 profile 同时激活时由 {@code ProductionSafetyGate} 按 {@link Simulated} 拦截启动。
 */
@AutoConfiguration(beforeName = "com.liroam.channel.config.LiroamChannelAutoConfiguration")
@Profile({"dev", "test"})
public class LiroamChannelXhsMockAutoConfiguration {

    @Bean(MockXhsChannelAdapter.ADAPTER_KEY)
    @Simulated
    MockXhsChannelAdapter mockXhs() {
        return new MockXhsChannelAdapter(Clock.systemUTC());
    }
}
