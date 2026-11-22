package com.liroam.pricing.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.price.BatchAdjust.Mode;
import com.liroam.pricing.testing.PricedCatalog;
import com.liroam.pricing.testing.PricingFixture;

/**
 * 批量调价（P6-D）：建议批量确认 / 驳回逐条独立（部分失败照常返回）；按涨跌百分比或目标毛利率批量改价，
 * 预览与执行同一口径，任一 SKU 低于最低毛利整批拒绝。汇率 0.9：日本成本 1980 分，中国 1260 分。
 */
class BatchRepricingTest {

    private PricingFixture f;
    private PricedCatalog c;
    private long jp;
    private long cn;

    @BeforeEach
    void setUp() {
        f = PricingFixture.create("pricing_batch");
        c = PricedCatalog.seed(f);
        jp = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
        cn = f.sku("CN-1", c.supplier, c.product, "CN", "ESIM", c.plan2gb);
        f.setFx("0.9");
        f.fxSync.run("system:fx");
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.prices.activateManual(cn, 2000, "manager:ops");
    }

    @Test
    void batchConfirmIsPerSuggestionAndReportsFailuresAlongsideSuccesses() {
        f.setFx("1.0");
        f.fxSync.run("system:fx");
        List<RepriceSuggestionRow> pending = f.suggestions.page("PENDING", 1, 10).records();
        assertThat(pending).hasSize(2);
        long jpSuggestion = suggestionOf(pending, jp);
        long cnSuggestion = suggestionOf(pending, cn);
        f.prices.rejectSuggestion(cnSuggestion, "manager:ops");

        BatchDecision result = f.batchRepricing.confirm(List.of(jpSuggestion, cnSuggestion, 99999L, jpSuggestion), "manager:ops");

        assertThat(result.done()).containsExactly(jpSuggestion);
        assertThat(result.failed()).extracting(BatchDecision.Failure::id).containsExactly(cnSuggestion, 99999L);
        assertThat(result.failed().get(0).message()).contains("REJECTED");
        assertThat(result.failed().get(1).message()).contains("不存在");
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3333);
        assertThat(f.prices.active(cn).orElseThrow().priceFen()).isEqualTo(2000);
    }

    @Test
    void batchRejectKeepsPrices() {
        f.setFx("1.0");
        f.fxSync.run("system:fx");
        List<Long> ids = f.suggestions.page("PENDING", 1, 10).records().stream().map(RepriceSuggestionRow::id).toList();

        BatchDecision result = f.batchRepricing.reject(ids, "manager:ops");

        assertThat(result.done()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(result.failed()).isEmpty();
        assertThat(f.suggestions.page("REJECTED", 1, 10).total()).isEqualTo(2);
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3000);
    }

    @Test
    void percentPreviewEqualsWhatApplyActivatesAndWritesBack() {
        BatchAdjust tenPercentUp = new BatchAdjust(List.of(jp, cn), Mode.PERCENT, new BigDecimal("0.1"));

        BatchPreview preview = f.batchRepricing.preview(tenPercentUp);

        assertThat(preview.belowMinCount()).isZero();
        assertThat(preview.rows()).extracting(BatchPreview.Row::skuBindingId, BatchPreview.Row::currentPriceFen, BatchPreview.Row::costCnyFen,
                BatchPreview.Row::newPriceFen).containsExactly(tuple(jp, 3000L, 1980L, 3300L), tuple(cn, 2000L, 1260L, 2200L));
        assertThat(preview.rows().get(0).marginAfter()).isEqualByComparingTo("0.4");
        assertThat(f.writer.calls).hasSize(2);

        BatchPreview applied = f.batchRepricing.apply(tenPercentUp, "manager:ops");

        assertThat(applied).isEqualTo(preview);
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3300);
        assertThat(f.prices.active(cn).orElseThrow().priceFen()).isEqualTo(2200);
        assertThat(f.writer.lastPrice).containsEntry("JP-1", 3300L).containsEntry("CN-1", 2200L);
        assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'PRICE_BATCH_ACTIVATE'")).isEqualTo(2);
    }

    @Test
    void targetMarginPricesFromLatestCostAndSupersedesPendingSuggestions() {
        f.setFx("1.0");
        f.fxSync.run("system:fx");
        // 汇率 1.0：日本成本 2200，中国 1400；目标毛利 0.4 → 成本 / 0.6，向上取整到分
        BatchAdjust target = new BatchAdjust(List.of(jp, cn), Mode.TARGET_MARGIN, new BigDecimal("0.4"));

        BatchPreview preview = f.batchRepricing.preview(target);

        assertThat(preview.rows()).extracting(BatchPreview.Row::newPriceFen).containsExactly(3667L, 2334L);
        f.batchRepricing.apply(target, "manager:ops");
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3667);
        assertThat(f.suggestions.page("PENDING", 1, 10).total()).isZero();
        assertThat(f.suggestions.page("SUPERSEDED", 1, 10).total()).isEqualTo(2);
    }

    @Test
    void anySkuBelowMinMarginRejectsTheWholeBatch() {
        // 降 40%：日本 1800 < 成本 1980，中国 1200 < 成本 1260
        BatchAdjust cut = new BatchAdjust(List.of(jp, cn), Mode.PERCENT, new BigDecimal("-0.4"));

        BatchPreview preview = f.batchRepricing.preview(cut);

        assertThat(preview.belowMinCount()).isEqualTo(2);
        assertThat(preview.rows()).extracting(BatchPreview.Row::belowMin).containsExactly(true, true);
        // 降 30%：日本 2100 毛利约 0.057 < 0.10；中国 1400 毛利恰为 0.10，不算低于
        BatchAdjust mixed = new BatchAdjust(List.of(jp, cn), Mode.PERCENT, new BigDecimal("-0.3"));
        assertThat(f.batchRepricing.preview(mixed).rows()).extracting(BatchPreview.Row::belowMin).containsExactly(true, false);
        assertThatThrownBy(() -> f.batchRepricing.apply(mixed, "manager:ops")).isInstanceOf(UserFacingException.class)
                .hasMessageContaining("最低毛利率").hasMessageContaining("JP-1");
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3000);
        assertThat(f.prices.active(cn).orElseThrow().priceFen()).isEqualTo(2000);
        assertThat(f.writer.calls).hasSize(2);
    }

    @Test
    void invalidBatchesAreRejectedBeforeAnyChange() {
        long unpriced = f.sku("HK-1", c.supplier, c.product, "HK", "ESIM", c.plan2gb);

        assertThatThrownBy(() -> f.batchRepricing.preview(new BatchAdjust(List.of(), Mode.PERCENT, new BigDecimal("0.1"))))
                .isInstanceOf(UserFacingException.class).hasMessageContaining("至少选一个");
        assertThatThrownBy(() -> f.batchRepricing.preview(new BatchAdjust(List.of(jp), Mode.PERCENT, BigDecimal.ZERO)))
                .isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.batchRepricing.preview(new BatchAdjust(List.of(jp), Mode.PERCENT, new BigDecimal("-1"))))
                .isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.batchRepricing.preview(new BatchAdjust(List.of(jp), Mode.TARGET_MARGIN, BigDecimal.ONE)))
                .isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.batchRepricing.preview(new BatchAdjust(List.of(jp), null, new BigDecimal("0.1"))))
                .isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.batchRepricing.apply(new BatchAdjust(List.of(jp, unpriced), Mode.PERCENT, new BigDecimal("0.1")), "manager:ops"))
                .isInstanceOf(UserFacingException.class).hasMessageContaining("HK-1").hasMessageContaining("还没有售价");
        assertThatThrownBy(() -> f.batchRepricing.preview(new BatchAdjust(List.of(99999L), Mode.PERCENT, new BigDecimal("0.1"))))
                .isInstanceOf(UserFacingException.class).hasMessageContaining("不存在");
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3000);
    }

    private static long suggestionOf(List<RepriceSuggestionRow> rows, long skuBindingId) {
        return rows.stream().filter(r -> r.skuBindingId() == skuBindingId).findFirst().orElseThrow().id();
    }
}
