package com.liroam.channel.adapter.mock;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.liroam.common.safety.Simulated;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.WebSurface;

/** 模拟渠道的通用 dev 控制台：只在 dev/test profile、管理端进程装配；各渠道的模拟实现由其适配器模块注册。 */
@AutoConfiguration
@Profile({"dev", "test"})
public class LiroamChannelMockAutoConfiguration {

    @Bean
    @Simulated
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    SimulatedChannelController liroamSimulatedChannelController(CurrentActor currentActor, ListableBeanFactory beans) {
        return new SimulatedChannelController(currentActor, beans);
    }
}
