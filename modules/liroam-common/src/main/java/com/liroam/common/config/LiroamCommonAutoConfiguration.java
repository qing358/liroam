package com.liroam.common.config;

import java.time.Clock;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.ops.OpsAlertController;
import com.liroam.common.ops.OpsAlertSchedule;
import com.liroam.common.ops.OpsAlertSource;
import com.liroam.common.ops.OpsAlerts;
import com.liroam.common.safety.ProductionSafetyGate;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.LiroamApiExceptionHandler;
import com.liroam.common.web.WebSurface;

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

    /** 告警来源由各模块注册 {@link OpsAlertSource} Bean；时钟随进程里唯一的 {@link Clock} Bean（测试可拨），没有则系统 UTC。 */
    @Bean
    OpsAlerts liroamOpsAlerts(ObjectProvider<OpsAlertSource> sources, ObjectProvider<Clock> clocks) {
        return new OpsAlerts(() -> sources.orderedStream().toList(), () -> clocks.getIfUnique(Clock::systemUTC).instant());
    }

    @Bean
    @ConditionalOnProperty(name = "liroam.worker.enabled", havingValue = "true")
    OpsAlertSchedule liroamOpsAlertSchedule(OpsAlerts liroamOpsAlerts) {
        return new OpsAlertSchedule(liroamOpsAlerts);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    OpsAlertController liroamOpsAlertController(CurrentActor currentActor, OpsAlerts liroamOpsAlerts) {
        return new OpsAlertController(currentActor, liroamOpsAlerts);
    }
}
