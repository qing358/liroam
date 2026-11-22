package com.liroam.pricing.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.pricing.fx.FxRates;
import com.liroam.pricing.fx.FxSync;
import com.liroam.pricing.mock.MockFxRateSource;
import com.liroam.pricing.price.CostCalculator;
import com.liroam.pricing.price.CostVersions;
import com.liroam.pricing.price.PriceVersions;
import com.liroam.pricing.price.RepriceSuggestions;
import com.liroam.pricing.price.Repricing;
import com.liroam.pricing.price.ShellPriceWriter;
import com.liroam.pricing.region.RegionDictionary;
import com.liroam.pricing.region.UsageRegionDescriber;
import com.liroam.pricing.snapshot.OrderLineSnapshots;
import com.liroam.pricing.tariff.TariffImports;
import com.liroam.schema.testing.ItSchema;

/** 一个测试库 + 按生产装配顺序手工拼起的定价服务；壳写回用记录型假实现。 */
public final class PricingFixture {

    public static final String SAMPLE_SHEET = "docs/media/202611-资费报价-想象智能-202611(20261117）.xlsx";

    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final JdbcClient client;
    public final AuditLog audit;
    public final Settings settings;
    public final TransactionTemplate tx;
    public final RecordingPriceWriter writer = new RecordingPriceWriter();
    public final MockFxRateSource mockFx = new MockFxRateSource();
    public final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T01:00:00Z"));
    public final SkuBindings bindings;
    public final UsageRegionDescriber describer;
    public final FxRates fxRates;
    public final CostVersions costVersions;
    public final CostCalculator costs;
    public final PriceVersions prices;
    public final RepriceSuggestions suggestions;
    public final Repricing repricing;
    public final TariffImports tariffs;
    public final FxSync fxSync;
    public final OrderLineSnapshots snapshots;

    private PricingFixture(DataSource ds) {
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        client = JdbcClient.create(ds);
        audit = new AuditLog(client);
        settings = new Settings(client, audit);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        bindings = new SkuBindings(client);
        describer = new UsageRegionDescriber(client, bindings);
        fxRates = new FxRates(client);
        costVersions = new CostVersions(client);
        costs = new CostCalculator(client, bindings, fxRates);
        prices = new PriceVersions(client, tx, writer, bindings, costs, costVersions, audit);
        suggestions = new RepriceSuggestions(client);
        repricing = new Repricing(client, tx, settings, bindings, costs, costVersions, prices, audit);
        tariffs = new TariffImports(client, tx, settings, audit, new SupplierDirectory(client), RegionDictionary.load(), repricing);
        fxSync = new FxSync(tx, settings, audit, () -> List.of(mockFx), fxRates, repricing, clock);
        snapshots = new OrderLineSnapshots(client, bindings, describer, fxRates, costVersions, prices);
    }

    public static PricingFixture create(String db) {
        return new PricingFixture(ItSchema.migrated(db));
    }

    public long supplier(String code) {
        jdbc.update("INSERT INTO lr_supplier (code, name, adapter_key, enabled, listing_enabled) VALUES (?, ?, ?, 1, 1)", code, code, code.toLowerCase());
        return jdbc.queryForObject("SELECT id FROM lr_supplier WHERE code = ?", Long.class, code);
    }

    public long planGb(String name, long gb) {
        jdbc.update("INSERT INTO lr_plan_template (name, data_quota_bytes, validity_days) VALUES (?, ?, 7)", name, gb * 1024L * 1024 * 1024);
        return jdbc.queryForObject("SELECT MAX(id) FROM lr_plan_template", Long.class);
    }

    public long cardProduct(long supplierId, String resourceCode) {
        return jdbc.queryForObject("SELECT id FROM lr_card_product WHERE supplier_id = ? AND card_type_code = ?", Long.class, supplierId, resourceCode);
    }

    public long regionId(String code) {
        return jdbc.queryForObject("SELECT id FROM lr_usage_region WHERE code = ?", Long.class, code);
    }

    public long regionGroup(String code, String... regionCodes) {
        jdbc.update("INSERT INTO lr_region_group (code, name_zh) VALUES (?, ?)", code, code);
        long id = jdbc.queryForObject("SELECT id FROM lr_region_group WHERE code = ?", Long.class, code);
        for (String region : regionCodes) {
            jdbc.update("INSERT INTO lr_region_group_member (group_id, region_id) VALUES (?, ?)", id, regionId(region));
        }
        return id;
    }

    /** 上架的新卡 SKU，单区域。 */
    public long sku(String shellSkuId, long supplierId, long cardProductId, String regionCode, String form, long planId) {
        return insertSku(shellSkuId, "NEW_CARD", supplierId, cardProductId, regionId(regionCode), null, form, planId, "LISTED");
    }

    public long groupSku(String shellSkuId, long supplierId, long cardProductId, long groupId, String form, long planId) {
        return insertSku(shellSkuId, "NEW_CARD", supplierId, cardProductId, null, groupId, form, planId, "LISTED");
    }

    public long insertSku(String shellSkuId, String kind, long supplierId, long cardProductId, Long regionId, Long groupId, String form,
            long planId, String listing) {
        jdbc.update("INSERT INTO lr_supplier_offering (supplier_id, card_product_id, region_id, region_group_id, plan_template_id, kind, "
                + "upstream_package_code) VALUES (?, ?, ?, ?, ?, 'BASE', ?)", supplierId, cardProductId, regionId, groupId, planId, "PKG-" + shellSkuId);
        long offering = jdbc.queryForObject("SELECT MAX(id) FROM lr_supplier_offering", Long.class);
        jdbc.update("INSERT INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, region_group_id, "
                + "form, plan_template_id, offering_id, listing_state) VALUES (?, 'G1', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                shellSkuId, kind, supplierId, cardProductId, regionId, groupId, form, planId, offering, listing);
        return jdbc.queryForObject("SELECT id FROM lr_sku_binding WHERE li_goods_sku_id = ?", Long.class, shellSkuId);
    }

    public int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    public void setFx(String rate) {
        mockFx.setRate(new BigDecimal(rate));
    }

    public static byte[] sampleSheet() {
        try {
            return Files.readAllBytes(repoRoot().resolve(SAMPLE_SHEET));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("deploy/ports.env"))) {
                return p;
            }
        }
        throw new IllegalStateException("找不到仓根");
    }

    /** 记录每次写回；{@link #failNext} 模拟壳侧写回失败。 */
    public static final class RecordingPriceWriter implements ShellPriceWriter {

        public final List<String> calls = new ArrayList<>();
        public final Map<String, Long> lastPrice = new LinkedHashMap<>();
        private boolean failNext;

        public void failNext() {
            failNext = true;
        }

        @Override
        public void writeSkuPrice(String liGoodsSkuId, long priceFen) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("壳写回失败（测试注入）");
            }
            calls.add(liGoodsSkuId + "=" + priceFen);
            lastPrice.put(liGoodsSkuId, priceFen);
        }
    }

    public static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        public void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
