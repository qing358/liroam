package com.liroam.fulfillment.testing;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.config.LiroamCommonAutoConfiguration;
import com.liroam.common.testing.ItDatabase;
import com.liroam.common.testing.TestKeys;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.fulfillment.pool.CardPool;
import com.liroam.fulfillment.pool.CardPool.PoolItemRow;
import com.liroam.fulfillment.worker.CommandExecutor;
import com.liroam.fulfillment.worker.FulfillmentWorker;
import com.liroam.schema.testing.ItSchema;
import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.contract.stub.ContractStubAdapter;
import com.liroam.supplier.spi.contract.stub.StubShape;
import com.liroam.supplier.spi.contract.stub.StubUpstream;

/**
 * 履约集成测试装配：已迁移的真库 + 本模块自动配置 + 两个形状的假上游（spi 契约桩）+ 假壳发货回写 + 可拨时钟。
 * <ul>
 *   <li>供应商 A（{@code SUP_A}，适配器 {@value #ADAPTER_A}）：POOL_IMPORT——池模式、导入/人工交码、无幂等键、三种状态操作、100/min。</li>
 *   <li>供应商 B（{@code SUP_B}，适配器 {@value #ADAPTER_B}）：CREATE_API——下单即建、API 交码、幂等键、只能终止、600/min。</li>
 * </ul>
 * 一个测试类一个实例（各自的测试库）；用例之间 {@link #reset()} 清台账、清假上游、拨回时钟，目录种子保留。
 */
public final class FulfillmentHarness implements AutoCloseable {

    public static final String ADAPTER_A = "stub-pool";
    public static final String ADAPTER_B = "stub-create";

    private static final List<String> LEDGER_TABLES = List.of("lr_fulfillment_line", "lr_card_asset", "lr_card_plan", "lr_card_pool_item",
            "lr_card_credential", "lr_supplier_command", "lr_supplier_call_log", "lr_manual_task", "lr_sensitive_access_log", "lr_topup_intent",
            "lr_shell_delivery", "lr_supplier_rate_bucket", "lr_setting", "lr_audit_log");

    public final String module;
    public final JdbcTemplate jdbc;
    public final MutableClock clock;
    public final RecordingShellOrderDelivery shell;
    public final StubUpstream upstreamA;
    public final StubUpstream upstreamB;
    public final ContractStubAdapter adapterA;
    public final ContractStubAdapter adapterB;
    /** 供应商 A 实际注册的适配器：默认原样转发 {@link #adapterA}，可收窄状态操作。 */
    public final NarrowableAdapter capsA;
    public final TestCatalog catalog;
    private final Map<String, Object> properties;
    private final AnnotationConfigApplicationContext context;

    private FulfillmentHarness(String module, DataSource ds, Map<String, Object> properties, MutableClock clock, RecordingShellOrderDelivery shell,
            StubUpstream upstreamA, StubUpstream upstreamB, ContractStubAdapter adapterA, ContractStubAdapter adapterB, TestCatalog catalog) {
        this.module = module;
        this.jdbc = new JdbcTemplate(ds);
        this.properties = properties;
        this.clock = clock;
        this.shell = shell;
        this.upstreamA = upstreamA;
        this.upstreamB = upstreamB;
        this.adapterA = adapterA;
        this.adapterB = adapterB;
        this.capsA = new NarrowableAdapter(adapterA);
        this.context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("liroam-test", properties));
        context.registerBean(DataSource.class, () -> ds);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(ds));
        context.registerBean(Clock.class, () -> clock);
        context.registerBean(ADAPTER_A, SupplierAdapter.class, () -> capsA);
        context.registerBean(ADAPTER_B, SupplierAdapter.class, () -> adapterB);
        context.registerBean(ShellOrderDelivery.class, () -> shell);
        context.registerBean(RecordingCredentialEvents.class);
        context.register(LiroamCommonAutoConfiguration.class, LiroamCatalogAutoConfiguration.class, LiroamFulfillmentAutoConfiguration.class);
        context.refresh();
        this.catalog = catalog != null ? catalog : TestCatalog.seed(jdbc);
    }

    public static FulfillmentHarness start(String module) {
        return start(module, Map.of());
    }

    public static FulfillmentHarness start(String module, Map<String, Object> extraProperties) {
        DataSource ds = ItSchema.migrated(module);
        Map<String, Object> props = new HashMap<>();
        for (String kv : TestKeys.properties()) {
            props.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
        }
        props.putAll(extraProperties);
        StubUpstream a = new StubUpstream(StubShape.POOL_IMPORT);
        StubUpstream b = new StubUpstream(StubShape.CREATE_API);
        // 两个桩各自从 1 编卡号，会撞出同一个 ICCID；把 A 的序号推远，台账里的卡号哈希唯一键才不会被假数据撞上
        for (int i = 0; i < 100_000; i++) {
            a.idleIccid();
        }
        a.reset();
        return new FulfillmentHarness(module, ds, props, new MutableClock(), new RecordingShellOrderDelivery(), a, b,
                new ContractStubAdapter(StubShape.POOL_IMPORT, a), new ContractStubAdapter(StubShape.CREATE_API, b), null);
    }

    /** 同一测试库上的第二个应用实例（各自连接源与 Bean），共享假上游、时钟与数据密钥。 */
    public FulfillmentHarness secondInstance() {
        return new FulfillmentHarness(module, ItDatabase.reopen(module), properties, clock, shell, upstreamA, upstreamB, adapterA, adapterB, catalog);
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public void reset() {
        LEDGER_TABLES.forEach(t -> jdbc.update("DELETE FROM " + t));
        jdbc.update("UPDATE lr_supplier SET rate_limit_per_min = NULL, enabled = 1, listing_enabled = 1");
        jdbc.update("UPDATE lr_sku_binding SET listing_state = 'LISTED', kyc_gate_mode = 'NONE'");
        upstreamA.reset();
        upstreamB.reset();
        capsA.reset();
        clock.reset();
        shell.reset();
        bean(RecordingCredentialEvents.class).reset();
    }

    /** 走唯一履约入口收一条自营订单行；返回履约行 id。 */
    public long acceptShop(String orderSn, String itemSn, long skuBindingId, int qty, String memberId) {
        return bean(FulfillmentEntry.class).accept(new FulfillmentRequest(SourceType.SHOP, orderSn, itemSn, skuBindingId, qty, memberId, null, null))
                .fulfillmentLineId();
    }

    public long acceptTopUp(String orderSn, String itemSn, long skuBindingId, String memberId, long cardAssetId) {
        return bean(FulfillmentEntry.class).accept(new FulfillmentRequest(SourceType.TOPUP, orderSn, itemSn, skuBindingId, 1, memberId, cardAssetId, null))
                .fulfillmentLineId();
    }

    /** 拆卡 + 指令执行反复跑到没有到期的活（推迟到未来的指令不等）。 */
    public void drain() {
        FulfillmentWorker worker = bean(FulfillmentWorker.class);
        CommandExecutor executor = bean(CommandExecutor.class);
        for (int i = 0; i < 30; i++) {
            if (worker.runOnce(50) + executor.runOnce(50, "test-worker") == 0) {
                return;
            }
        }
        throw new IllegalStateException("drain 30 轮仍未收敛");
    }

    /** 往供应商 A 的卡池导入 n 张上游空闲卡；{@code withLpa} 时随批带交码内容。 */
    public List<String> importPoolA(int n, boolean withLpa) {
        List<String> iccids = new ArrayList<>();
        List<PoolItemRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String iccid = upstreamA.idleIccid();
            iccids.add(iccid);
            rows.add(new PoolItemRow(iccid, withLpa ? "LPA:1$pool.example$" + iccid : null));
        }
        bean(CardPool.class).importItems(catalog.supplierA, catalog.productA, rows, "op-test");
        return iccids;
    }

    /** 收一张单卡订单并拆卡；返回卡 id（指令尚未执行）。 */
    public long acceptCard(String orderSn, long skuBindingId, String memberId) {
        long line = acceptShop(orderSn, orderSn + "-1", skuBindingId, 1, memberId);
        bean(FulfillmentWorker.class).runOnce(10);
        return cardOfLine(line);
    }

    public long cardOfLine(long lineId) {
        return jdbc.queryForObject("SELECT id FROM lr_card_asset WHERE fulfillment_line_id = ? ORDER BY unit_seq LIMIT 1", Long.class, lineId);
    }

    public Map<String, Object> command(String type, long cardId) {
        return jdbc.queryForMap("SELECT * FROM lr_supplier_command WHERE type = ? AND card_asset_id = ? ORDER BY id DESC LIMIT 1", type, cardId);
    }

    public int count(String sql, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + sql, Integer.class, args);
    }

    public String status(String table, long id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = ?", String.class, id);
    }

    @Override
    public void close() {
        context.close();
    }
}
