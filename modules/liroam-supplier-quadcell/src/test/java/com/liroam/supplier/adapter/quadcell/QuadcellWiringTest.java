package com.liroam.supplier.adapter.quadcell;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.safety.Simulated;
import com.liroam.common.testing.TestKeys;
import com.liroam.supplier.adapter.quadcell.config.LiroamSupplierQuadcellAutoConfiguration;
import com.liroam.supplier.spi.SupplierAdapter;

/** 有 key 才装配；真适配器不是模拟实现，生产 profile 下可启动。 */
class QuadcellWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamSupplierQuadcellAutoConfiguration.class))
            .withPropertyValues(TestKeys.properties());

    @Test
    void withoutApiKeyNothingIsWired() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(SupplierAdapter.class));
        runner.withPropertyValues("liroam.supplier.quadcell.api-key=  ")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(SupplierAdapter.class));
    }

    @Test
    void apiKeyWiresAdapterUnderItsAdapterKeyEvenInProduction() {
        runner.withPropertyValues("spring.profiles.active=prod", "liroam.supplier.quadcell.api-key=k").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(SupplierAdapter.class)).containsOnlyKeys(QuadcellAdapter.ADAPTER_KEY);
            assertThat(ctx.getBeanNamesForAnnotation(Simulated.class)).isEmpty();
            QuadcellProperties p = ctx.getBean(QuadcellProperties.class);
            assertThat(p.getBaseUrl()).isEqualTo(QuadcellProperties.OFFICIAL_BASE_URL);
            assertThat(p.getDefaultMode()).as("不替运营猜 mode").isNull();
        });
    }

    @Test
    void environmentVariablesBind() {
        Map<String, Object> env = Map.of("LIROAM_SUPPLIER_QUADCELL_APIKEY", "k", "LIROAM_SUPPLIER_QUADCELL_BASEURL", "http://127.0.0.1:1/open-api/1.0",
                "LIROAM_SUPPLIER_QUADCELL_READTIMEOUT", "7s", "LIROAM_SUPPLIER_QUADCELL_DEFAULTMODE", "2");
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env))).run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasBean(QuadcellAdapter.ADAPTER_KEY);
                    QuadcellProperties p = ctx.getBean(QuadcellProperties.class);
                    assertThat(p.getBaseUrl()).isEqualTo("http://127.0.0.1:1/open-api/1.0");
                    assertThat(p.getReadTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(p.getDefaultMode()).isEqualTo(2);
                });
    }

    @Test
    void blankBaseUrlFailsStartup() {
        runner.withPropertyValues("liroam.supplier.quadcell.api-key=k", "liroam.supplier.quadcell.base-url=")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("base-url"));
    }
}
