package com.liroam.common.config;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.safety.ProductionSafetyGate;
import com.liroam.common.web.LiroamApiExceptionHandler;

@AutoConfiguration
public class LiroamCommonAutoConfiguration {

    @Bean
    static ProductionSafetyGate liroamProductionSafetyGate(ListableBeanFactory beans, Environment env) {
        return new ProductionSafetyGate(beans, env);
    }

    /** 密钥缺失或格式不对即启动失败，不等到第一次写 ICCID 才暴露。 */
    @Bean
    SensitiveCipher liroamSensitiveCipher(Environment env) {
        return SensitiveCipher.fromEnvironment(env::getProperty);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    LiroamApiExceptionHandler liroamApiExceptionHandler() {
        return new LiroamApiExceptionHandler();
    }
}
