package com.liroam.supplier.mock.config;

import java.time.Clock;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.liroam.common.safety.Simulated;
import com.liroam.supplier.mock.MockFaults;
import com.liroam.supplier.mock.MockShapeAAdapter;
import com.liroam.supplier.mock.MockShapeBAdapter;
import com.liroam.supplier.mock.MockSupplierProperties;
import com.liroam.supplier.mock.MockUpstreamConsole;

/**
 * 两家模拟供应商，只在 dev/test profile 装配；Bean 名即 {@code lr_supplier.adapter_key}。
 * 误把 test 与生产 profile 同时激活时由 {@code ProductionSafetyGate} 按 {@link Simulated} 拦截启动。
 */
@AutoConfiguration
@Profile({"dev", "test"})
@EnableConfigurationProperties(MockSupplierProperties.class)
public class LiroamSupplierMockAutoConfiguration {

    @Bean
    @Simulated
    MockFaults liroamMockFaults(MockSupplierProperties properties) {
        return new MockFaults(properties.isRulesEnabled() ? properties.getRules() : List.of());
    }

    @Bean(MockShapeAAdapter.ADAPTER_KEY)
    @Simulated
    MockShapeAAdapter mockShapeA(MockFaults liroamMockFaults) {
        return new MockShapeAAdapter(liroamMockFaults, Clock.systemUTC());
    }

    @Bean(MockShapeBAdapter.ADAPTER_KEY)
    @Simulated
    MockShapeBAdapter mockShapeB(MockFaults liroamMockFaults, MockSupplierProperties properties) {
        return new MockShapeBAdapter(liroamMockFaults, Clock.systemUTC(), properties.getWebhookSecret());
    }

    @Bean
    @Simulated
    MockUpstreamConsole liroamMockUpstreamConsole(MockShapeAAdapter mockShapeA, MockShapeBAdapter mockShapeB) {
        return new MockUpstreamConsole(List.of(mockShapeA, mockShapeB));
    }
}
