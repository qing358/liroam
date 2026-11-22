package com.liroam.common.config;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.safety.ProductionSafetyGate;

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
}
