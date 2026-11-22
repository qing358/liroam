package com.liroam.supplier.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.TestKeys;
import com.liroam.supplier.mock.config.LiroamSupplierMockAutoConfiguration;
import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;

/** mock 只在 dev/test 装配；生产 profile 不装配，误配即启动失败（CT-MOCK-01）。 */
class MockWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LiroamCommonAutoConfiguration.class, LiroamSupplierMockAutoConfiguration.class))
            .withPropertyValues(TestKeys.properties());

    @Test
    void devAndTestProfilesWireBothAdaptersByAdapterKey() {
        for (String profile : new String[] {"dev", "test"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBeansOfType(SupplierAdapter.class)).containsOnlyKeys(MockShapeAAdapter.ADAPTER_KEY, MockShapeBAdapter.ADAPTER_KEY);
                assertThat(ctx).hasSingleBean(MockFaults.class).hasSingleBean(MockUpstreamConsole.class);
            });
        }
    }

    @Test
    void upstreamConsoleEndpointOnlyOnManagerSurface() {
        runner.withPropertyValues("spring.profiles.active=dev", "liroam.web.surface=manager")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(MockUpstreamController.class));
        runner.withPropertyValues("spring.profiles.active=dev", "liroam.web.surface=buyer")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(MockUpstreamController.class));
    }

    @Test
    void noProfileOrProductionProfileWiresNoMock() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(SupplierAdapter.class).doesNotHaveBean(MockFaults.class));
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(SupplierAdapter.class).doesNotHaveBean(MockUpstreamConsole.class));
    }

    @Test
    void ct_mock_01_testProfileActivatedAlongsideProductionFailsStartupNamingTheBeans() {
        runner.withPropertyValues("spring.profiles.active=prod,test").run(ctx -> assertThat(ctx).hasFailed().getFailure()
                .hasMessageContaining("@Simulated").hasMessageContaining(MockShapeAAdapter.ADAPTER_KEY).hasMessageContaining(MockShapeBAdapter.ADAPTER_KEY));
    }

    @Test
    void rulesCanBeSwitchedOffByProperty() {
        runner.withPropertyValues("spring.profiles.active=dev", "liroam.mock.supplier.rules-enabled=false").run(ctx -> {
            SupplierAdapter a = ctx.getBean(MockShapeAAdapter.ADAPTER_KEY, SupplierAdapter.class);
            assertThat(a.provision(new ProvisionCommand("PROVISION-1-0-1", 1, "898880000000000" + "9991", "5GB-7D", null)))
                    .isInstanceOf(Outcome.Ok.class);
        });
    }

    @Test
    void configuredRulesReplaceDefaults() {
        runner.withPropertyValues("spring.profiles.active=dev",
                "liroam.mock.supplier.rules[0].adapter-key=mock-shape-b",
                "liroam.mock.supplier.rules[0].offering-code=1GB-1D",
                "liroam.mock.supplier.rules[0].failure=SERVER_ERROR").run(ctx -> {
                    SupplierAdapter a = ctx.getBean(MockShapeAAdapter.ADAPTER_KEY, SupplierAdapter.class);
                    SupplierAdapter b = ctx.getBean(MockShapeBAdapter.ADAPTER_KEY, SupplierAdapter.class);
                    assertThat(b.provision(new ProvisionCommand("PROVISION-1-0-1", 1, null, "1GB-1D", null))).isInstanceOf(Outcome.Retryable.class);
                    assertThat(b.provision(new ProvisionCommand("PROVISION-1-0-1", 1, null, "1GB-1D", null))).isInstanceOf(Outcome.Ok.class);
                    assertThat(a.provision(new ProvisionCommand("PROVISION-2-0-1", 2, "898880000000000" + "9991", "5GB-7D", null)))
                            .as("默认表已被替换").isInstanceOf(Outcome.Ok.class);
                });
    }

    @Test
    void ruleWithoutAnyMatchCriterionFailsStartup() {
        runner.withPropertyValues("spring.profiles.active=dev",
                "liroam.mock.supplier.rules[0].adapter-key=mock-shape-a",
                "liroam.mock.supplier.rules[0].failure=THROTTLED")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause().hasMessageContaining("iccidSuffix 或 offeringCode"));
    }

    @Test
    void webhookSecretComesFromConfiguration() {
        runner.withPropertyValues("spring.profiles.active=dev", "liroam.mock.supplier.webhook-secret=configured").run(ctx -> {
            SupplierAdapter b = ctx.getBean(MockShapeBAdapter.ADAPTER_KEY, SupplierAdapter.class);
            byte[] body = MockUpstreamFixture.callbackBody("MB-r", "MB-r", "ACTIVE", 1, 0, "2026-11-22T04:00:00Z", "2026-11-23T04:00:00Z");
            assertThat(b.webhook().orElseThrow().parse(Map.of("X-Mock-Signature", MockUpstreamFixture.sign("configured", body)), body))
                    .isInstanceOf(Outcome.Ok.class);
            assertThat(b.webhook().orElseThrow().parse(Map.of("X-Mock-Signature",
                    MockUpstreamFixture.sign(MockSupplierProperties.DEV_WEBHOOK_SECRET, body)), body)).isInstanceOf(Outcome.Rejected.class);
        });
    }

    @Test
    void consoleDrivesTheWiredAdapters() {
        runner.withPropertyValues("spring.profiles.active=test").run(ctx -> {
            SupplierAdapter a = ctx.getBean(MockShapeAAdapter.ADAPTER_KEY, SupplierAdapter.class);
            String iccid = MockUpstreamFixture.freshIccid();
            var r = ((Outcome.Ok<ProvisionResult>) a.provision(new ProvisionCommand("PROVISION-3-0-1", 3, iccid, "5GB-7D", null))).value();
            ctx.getBean(MockUpstreamConsole.class).activate(MockShapeAAdapter.ADAPTER_KEY, r.upstreamRef(), 0);
            var usage = ((Outcome.Ok<UsageSnapshot>) a.queryUsage(new CardRef(3, iccid, r.upstreamRef()))).value();
            assertThat(usage.status()).isEqualTo(CardStatus.ACTIVE);
        });
    }
}
