package com.liroam.pricing.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.pricing.testing.PricedCatalog;
import com.liroam.pricing.testing.PricingFixture;

/**
 * 调价报表（P6-D）：每个在售 SKU 的售价 / 最近成本 / 毛利率 / 是否低于最低毛利 / 待确认建议，表头带最近汇率。
 * 汇率 0.9：日本 2GB eSIM 成本 1980 分，中国 1260 分，香港 1080 分；最低毛利率默认 0.10。
 */
class PriceReportTest {

    private PricingFixture f;
    private PricedCatalog c;

    @BeforeEach
    void setUp() {
        f = PricingFixture.create("pricing_report");
        c = PricedCatalog.seed(f);
    }

    @Test
    void reportShowsPriceCostMarginBelowMinAndPendingSuggestionWithLatestFx() {
        long jp = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
        long cn = f.sku("CN-1", c.supplier, c.product, "CN", "ESIM", c.plan2gb);
        long unpriced = f.sku("HK-1", c.supplier, c.product, "HK", "ESIM", c.plan2gb);
        f.insertSku("JP-D", "NEW_CARD", c.supplier, c.product, f.regionId("JP"), null, "ESIM", c.plan2gb, "DRAFT");
        f.setFx("0.9");
        f.fxSync.run("system:fx");
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.prices.activateManual(cn, 1300, "manager:ops");

        PriceReportView before = f.priceReport.build();

        assertThat(before.fxRate()).isEqualByComparingTo("0.9");
        assertThat(before.fxStale()).isFalse();
        Map<Long, PriceReportRow> rows = byId(before);
        assertThat(rows).containsOnlyKeys(jp, cn, unpriced);
        PriceReportRow jpRow = rows.get(jp);
        assertThat(jpRow.liGoodsSkuId()).isEqualTo("JP-1");
        assertThat(jpRow.supplierCode()).isEqualTo("SUP_A");
        assertThat(jpRow.regionText()).isEqualTo("日本");
        assertThat(jpRow.form()).isEqualTo("ESIM");
        assertThat(jpRow.priceFen()).isEqualTo(3000);
        assertThat(jpRow.costCnyFen()).isEqualTo(1980);
        assertThat(jpRow.marginRate()).isEqualByComparingTo("0.34");
        assertThat(jpRow.minMarginRate()).isEqualByComparingTo("0.10");
        assertThat(jpRow.belowMin()).isFalse();
        assertThat(jpRow.pendingSuggestionId()).isNull();
        // 1300 售价、1260 成本：毛利率约 3% < 10%
        assertThat(rows.get(cn).belowMin()).isTrue();
        PriceReportRow hk = rows.get(unpriced);
        assertThat(hk.priceFen()).isNull();
        assertThat(hk.costCnyFen()).isEqualTo(1080);
        assertThat(hk.marginRate()).isNull();
        assertThat(hk.belowMin()).isFalse();

        f.setFx("1.0");
        f.fxSync.run("system:fx");

        PriceReportView after = f.priceReport.build();
        assertThat(after.fxRate()).isEqualByComparingTo("1.0");
        PriceReportRow moved = byId(after).get(jp);
        assertThat(moved.costCnyFen()).isEqualTo(2200);
        assertThat(moved.marginRate()).isEqualByComparingTo("0.266667");
        long pendingId = f.suggestions.page("PENDING", 1, 10).records().stream()
                .filter(s -> s.skuBindingId() == jp).findFirst().orElseThrow().id();
        assertThat(moved.pendingSuggestionId()).isEqualTo(pendingId);
        assertThat(moved.suggestedPriceFen()).isEqualTo(3333);
    }

    @Test
    void skuScopedMinMarginAndPricingRuleMinMarginAreReported() {
        long jp = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
        long cn = f.sku("CN-1", c.supplier, c.product, "CN", "ESIM", c.plan2gb);
        f.setFx("0.9");
        f.fxSync.run("system:fx");
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.prices.activateManual(cn, 2000, "manager:ops");
        f.jdbc.update("INSERT INTO lr_pricing_rule (scope_type, scope_id, markup_mode, markup_rate, min_margin_rate) VALUES ('SKU', ?, 'MARGIN_RATE', 0.5, 0.4)", jp);

        Map<Long, PriceReportRow> rows = byId(f.priceReport.build());

        assertThat(rows.get(jp).minMarginRate()).isEqualByComparingTo("0.4");
        assertThat(rows.get(jp).belowMin()).isTrue();
        assertThat(rows.get(cn).minMarginRate()).isEqualByComparingTo("0.10");
        assertThat(rows.get(cn).belowMin()).isFalse();
    }

    @Test
    void neverSyncedFxIsReportedAsStaleWithoutRate() {
        PriceReportView view = f.priceReport.build();

        assertThat(view.fxRate()).isNull();
        assertThat(view.fxStale()).isTrue();
        assertThat(view.rows()).isEmpty();
    }

    private static Map<Long, PriceReportRow> byId(PriceReportView view) {
        return view.rows().stream().collect(Collectors.toMap(PriceReportRow::skuBindingId, Function.identity()));
    }
}
