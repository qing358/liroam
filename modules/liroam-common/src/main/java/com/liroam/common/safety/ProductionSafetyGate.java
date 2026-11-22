package com.liroam.common.safety;

import java.util.Arrays;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

/** 生产 profile 下发现 {@link Simulated} Bean 即抛异常，让应用启动失败。 */
public final class ProductionSafetyGate implements SmartInitializingSingleton {

    private final ListableBeanFactory beans;
    private final Environment env;

    public ProductionSafetyGate(ListableBeanFactory beans, Environment env) {
        this.beans = beans;
        this.env = env;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!ProductionProfiles.isProduction(env)) {
            return;
        }
        String[] simulated = beans.getBeanNamesForAnnotation(Simulated.class);
        if (simulated.length > 0) {
            throw new IllegalStateException("生产环境（profile " + Arrays.toString(env.getActiveProfiles())
                    + "）不得装配模拟实现 @Simulated：" + String.join(", ", simulated));
        }
    }
}
