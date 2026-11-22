package com.liroam.smoke;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.TestKeys;
import com.liroam.fulfillment.FulfillmentAck;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.fulfillment.pool.CardPool;
import com.liroam.fulfillment.pool.CardPool.PoolItemRow;
import com.liroam.fulfillment.testing.MutableClock;
import com.liroam.fulfillment.testing.RecordingCredentialEvents;
import com.liroam.fulfillment.testing.RecordingShellOrderDelivery;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.fulfillment.testing.TestCatalog.Upstreams;
import com.liroam.fulfillment.worker.CommandExecutor;
import com.liroam.fulfillment.worker.FulfillmentWorker;
import com.liroam.schema.testing.ItSchema;
import com.liroam.supplier.mock.MockShapeAAdapter;
import com.liroam.supplier.mock.MockShapeBAdapter;
import com.liroam.supplier.mock.config.LiroamSupplierMockAutoConfiguration;

/**
 * 冒烟装配：已迁移的测试库 + common / catalog / supplier-mock / fulfillment 自动配置（test profile），
 * 供应商 A、B 分别指向真实的 {@value MockShapeAAdapter#ADAPTER_KEY} / {@value MockShapeBAdapter#ADAPTER_KEY}；
 * 壳只换成记录型发货回写（H5 端口）与可拨时钟。目录种子与履约测试同一份 {@link TestCatalog}，先于启动写入，走启动时的适配器校验。
 */
final class SmokeHarness implements AutoCloseable {

    /** 套餐码须是 mock 认得的格式，额度与天数和 {@link TestCatalog} 的模板一致。 */
    static final Upstreams MOCKS = new Upstreams(MockShapeAAdapter.ADAPTER_KEY, MockShapeBAdapter.ADAPTER_KEY, "3GB-5D", "1GB-5D", "5GB-7D",
            "2GB-7D");

    final JdbcTemplate jdbc;
    final TestCatalog catalog;
    final MutableClock clock = new MutableClock();
    final RecordingShellOrderDelivery shell = new RecordingShellOrderDelivery();
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

    SmokeHarness(String module) {
        DataSource ds = ItSchema.migrated(module);
        jdbc = new JdbcTemplate(ds);
        catalog = TestCatalog.seed(jdbc, MOCKS);
        context.getEnvironment().setActiveProfiles("test");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, TestKeys.properties());
        context.registerBean(DataSource.class, () -> ds);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(ShellOrderDelivery.class, () -> shell);
        context.registerBean(RecordingCredentialEvents.class);
        context.register(LiroamCommonAutoConfiguration.class, LiroamCatalogAutoConfiguration.class, LiroamSupplierMockAutoConfiguration.class,
                LiroamFulfillmentAutoConfiguration.class);
        context.refresh();
    }

    <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    <T> T bean(String name, Class<T> type) {
        return context.getBean(name, type);
    }

    FulfillmentAck acceptShop(String orderSn, String itemSn, long skuBindingId, int qty, String memberId) {
        return bean(FulfillmentEntry.class).accept(new FulfillmentRequest(SourceType.SHOP, orderSn, itemSn, skuBindingId, qty, memberId, null, null));
    }

    FulfillmentAck acceptTopUp(String orderSn, String itemSn, long skuBindingId, String memberId, long cardAssetId) {
        return bean(FulfillmentEntry.class).accept(new FulfillmentRequest(SourceType.TOPUP, orderSn, itemSn, skuBindingId, 1, memberId, cardAssetId, null));
    }

    /** 运营往供应商 A 的卡池导入空白卡（不带交码，交码之后走导入或人工录入）。 */
    void importPoolA(List<String> iccids) {
        bean(CardPool.class).importItems(catalog.supplierA, catalog.productA, iccids.stream().map(i -> new PoolItemRow(i, null)).toList(), "op-smoke");
    }

    /**
     * 像节拍一样反复跑拆卡与指令执行；没活可干时把时钟往前拨一分钟（让退避、限流等待、查档到期），连续几轮都空才算收敛。
     * 不跑轮询排队，免得拨钟引出与本用例无关的查询指令。
     */
    void drain() {
        FulfillmentWorker worker = bean(FulfillmentWorker.class);
        CommandExecutor executor = bean(CommandExecutor.class);
        int idle = 0;
        for (int round = 0; round < 60; round++) {
            if (worker.runOnce(50) + executor.runOnce(50, "smoke-worker") > 0) {
                idle = 0;
                continue;
            }
            if (++idle > 5) {
                return;
            }
            clock.advance(Duration.ofMinutes(1));
        }
        throw new IllegalStateException("drain 60 轮仍未收敛");
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + sql, Integer.class, args);
    }

    @Override
    public void close() {
        context.close();
    }
}
