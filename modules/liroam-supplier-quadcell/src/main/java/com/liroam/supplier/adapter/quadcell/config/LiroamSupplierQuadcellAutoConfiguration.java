package com.liroam.supplier.adapter.quadcell.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.liroam.supplier.adapter.quadcell.QuadcellAdapter;
import com.liroam.supplier.adapter.quadcell.QuadcellProperties;

/**
 * 只在配置了 API key（如环境变量 {@code LIROAM_SUPPLIER_QUADCELL_APIKEY}）时装配，任何 profile 都可用。
 * 没有 key 而 {@code lr_supplier} 里启用了 {@code adapter_key=quadcell} 的供应商时，由目录装配检查让启动失败。
 */
@AutoConfiguration
@EnableConfigurationProperties(QuadcellProperties.class)
@Conditional(LiroamSupplierQuadcellAutoConfiguration.ApiKeyPresent.class)
public class LiroamSupplierQuadcellAutoConfiguration {

    @Bean(QuadcellAdapter.ADAPTER_KEY)
    QuadcellAdapter quadcell(QuadcellProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException(QuadcellProperties.PREFIX + ".base-url 不能为空");
        }
        return new QuadcellAdapter(properties);
    }

    /** 与属性绑定同一套解析（含环境变量写法），避免「条件看不到、绑定看得到」。 */
    static final class ApiKeyPresent implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return Binder.get(context.getEnvironment()).bind(QuadcellProperties.PREFIX + ".api-key", String.class)
                    .map(key -> !key.isBlank()).orElse(false);
        }
    }
}
