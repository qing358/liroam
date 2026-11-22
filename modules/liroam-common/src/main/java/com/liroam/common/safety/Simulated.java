package com.liroam.common.safety;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记模拟实现（mock 供应商、mock 汇率来源、mock 渠道等）。
 * 生产 profile 下容器里出现任何带此标记的 Bean 即启动失败（{@link ProductionSafetyGate}）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Simulated {
}
