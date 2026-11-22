package com.liroam.pricing.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.setting.SettingScope;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.PricingSettings;
import com.liroam.pricing.testing.PricedCatalog;
import com.liroam.pricing.testing.PricingFixture;

/** 成本、售价版本与调价建议（§8.3–8.4、CT-PRC-02、§15 V8/V9）。汇率 0.9 时日本 2GB eSIM 成本 1980 分。 */
class RepricingTest {

    private PricingFixture f;
    private PricedCatalog c;
    private long jp;

    @BeforeEach
    void setUp() {
        f = PricingFixture.create("pricing_reprice");
        c = PricedCatalog.seed(f);
        jp = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
        f.setFx("0.9");
        f.fxSync.run("system:fx");
    }

    @Test
    void costIsDataGbTimesRegionCostPlusCardFeeTimesFx() {
        CostVersion cost = f.costVersions.latest(jp).orElseThrow();

        assertThat(cost.costHkdMinor()).isEqualTo(2200);
        assertThat(cost.costCnyFen()).isEqualTo(1980);
        assertThat(cost.tariffSheetId()).isEqualTo(c.sheet);
        assertThat(cost.breakdownJson()).contains("\"dataGb\"").contains("\"cardFeeHkd\"").contains("\"fxRate\"");
    }

    @Test
    void physicalFormTopUpAndGroupBranchesOfCost() {
        long physical = f.sku("JP-P", c.supplier, c.product, "JP", "PHYSICAL", c.plan2gb);
        long topUp = f.insertSku("JP-T", "TOP_UP", c.supplier, c.product, f.regionId("JP"), null, "SOFT", c.plan2gb, "LISTED");
        long group = f.groupSku("CNHK", c.supplier, c.product, f.regionGroup("CN_HK", "CN", "HK"), "ESIM", c.plan2gb);

        assertThat(f.costs.quote(physical).costHkdMinor()).isEqualTo(2100);
        assertThat(f.costs.quote(topUp).costHkdMinor()).isEqualTo(2000);
        // 区域组取成员最大：中国 6 > 香港 5
        assertThat(f.costs.quote(group).costHkdMinor()).isEqualTo(1400);
    }

    @Test
    void costUnavailableWithoutRegionTariffOrUnlimitedPlan() {
        f.jdbc.update("INSERT INTO lr_usage_region (code, name_zh) VALUES ('KR', '韩国')");
        long kr = f.sku("KR-1", c.supplier, c.product, "KR", "ESIM", c.plan2gb);
        f.jdbc.update("INSERT INTO lr_plan_template (name, data_quota_bytes, validity_days) VALUES ('不限', NULL, 7)");
        long unlimited = f.sku("JP-U", c.supplier, c.product, "JP", "ESIM", f.jdbc.queryForObject("SELECT MAX(id) FROM lr_plan_template", Long.class));

        assertThatThrownBy(() -> f.costs.quote(kr)).isInstanceOf(CostUnavailableException.class).hasMessageContaining("韩国");
        assertThatThrownBy(() -> f.costs.quote(unlimited)).isInstanceOf(CostUnavailableException.class).hasMessageContaining("不限");
        assertThatThrownBy(() -> f.prices.activateManual(kr, 3000, "manager:ops")).isInstanceOf(UserFacingException.class);
    }

    @Test
    void ct_prc_02_manualConfirmOnlySuggestsUntilConfirmedThenWritesBackActivePrice() {
        f.prices.activateManual(jp, 3000, "manager:ops");
        assertThat(f.writer.lastPrice).containsEntry("JP-1", 3000L);

        f.setFx("1.0");
        f.fxSync.run("system:fx");

        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3000);
        assertThat(f.writer.calls).hasSize(1);
        PageResult<RepriceSuggestionRow> page = f.suggestions.page("PENDING", 1, 10);
        assertThat(page.total()).isEqualTo(1);
        RepriceSuggestionRow s = page.records().get(0);
        assertThat(s.liGoodsSkuId()).isEqualTo("JP-1");
        assertThat(s.triggerKind()).isEqualTo("FX");
        assertThat(s.currentPriceFen()).isEqualTo(3000);
        assertThat(s.marginBefore()).isEqualByComparingTo("0.340000");
        assertThat(s.marginAfter()).isEqualByComparingTo("0.266667");
        // 无加价规则：保持原毛利率 2200 / (1 - 0.34)
        assertThat(s.suggestedPriceFen()).isEqualTo(3333);

        ActivePrice confirmed = f.prices.confirmSuggestion(s.id(), "manager:ops");

        assertThat(confirmed.priceFen()).isEqualTo(3333);
        assertThat(confirmed.origin()).isEqualTo("SUGGESTION");
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3333);
        assertThat(f.writer.lastPrice.get("JP-1")).isEqualTo(f.prices.active(jp).orElseThrow().priceFen());
        assertThat(f.suggestions.page("CONFIRMED", 1, 10).total()).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM lr_sku_price_version WHERE sku_binding_id = ? AND status = 'ACTIVE'", jp)).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'REPRICE_CONFIRM'")).isEqualTo(1);
        assertThatThrownBy(() -> f.prices.confirmSuggestion(s.id(), "manager:ops")).isInstanceOf(UserFacingException.class);
    }

    @Test
    void newSuggestionSupersedesPendingAndRejectKeepsPrice() {
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.setFx("1.0");
        f.fxSync.run("system:fx");
        f.setFx("1.1");
        f.fxSync.run("system:fx");

        assertThat(f.suggestions.page("SUPERSEDED", 1, 10).total()).isEqualTo(1);
        List<RepriceSuggestionRow> pending = f.suggestions.page("PENDING", 1, 10).records();
        assertThat(pending).hasSize(1);

        f.prices.rejectSuggestion(pending.get(0).id(), "manager:ops");

        assertThat(f.suggestions.page("REJECTED", 1, 10).total()).isEqualTo(1);
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3000);
        assertThat(f.suggestions.page(null, 1, 10).total()).isEqualTo(2);
    }

    @Test
    void smallMoveAboveMinMarginProducesNothing() {
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.setFx("0.905");

        f.fxSync.run("system:fx");

        assertThat(f.count("SELECT COUNT(*) FROM lr_reprice_suggestion")).isZero();
        assertThat(f.costVersions.latest(jp).orElseThrow().costCnyFen()).isEqualTo(1991);
    }

    @Test
    void belowMinMarginSuggestsEvenWithoutMove() {
        f.prices.activateManual(jp, 2100, "manager:ops");

        f.repricing.recomputeAll(RepriceTrigger.TARIFF, "manager:ops");

        RepriceSuggestionRow s = f.suggestions.page("PENDING", 1, 10).records().get(0);
        assertThat(s.triggerKind()).isEqualTo("TARIFF");
        // 最低毛利 0.10：1980 / 0.9
        assertThat(s.suggestedPriceFen()).isEqualTo(2200);
    }

    @Test
    void tariffActivationTriggersRecompute() {
        f.prices.activateManual(jp, 3000, "manager:ops");
        long next = f.tariffs.importXlsx(c.supplier, "r1b.xlsx", new ByteArrayInputStream(PricedCatalog.sheet("14")), "manager:ops");

        f.tariffs.activate(next, "manager:ops");

        assertThat(f.costVersions.latest(jp).orElseThrow().tariffSheetId()).isEqualTo(next);
        assertThat(f.suggestions.page("PENDING", 1, 10).records()).singleElement()
                .satisfies(s -> assertThat(s.triggerKind()).isEqualTo("TARIFF"));
    }

    @Test
    void v9_autoModeActivatesNewPriceDirectlyWithAudit() {
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.settings.set(PricingSettings.REPRICE_MODE, SettingScope.GLOBAL, RepriceMode.AUTO, "manager:ops");
        f.setFx("1.0");

        f.fxSync.run("system:fx");

        ActivePrice active = f.prices.active(jp).orElseThrow();
        assertThat(active.priceFen()).isEqualTo(3333);
        assertThat(active.origin()).isEqualTo("AUTO");
        assertThat(f.writer.lastPrice).containsEntry("JP-1", 3333L);
        assertThat(f.count("SELECT COUNT(*) FROM lr_reprice_suggestion")).isZero();
        assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'PRICE_AUTO_ACTIVATE' AND target_id = ?", String.valueOf(jp))).isEqualTo(1);
    }

    @Test
    void skuScopedModeOverridesGlobal() {
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.settings.set(PricingSettings.REPRICE_MODE, SettingScope.GLOBAL, RepriceMode.AUTO, "manager:ops");
        f.settings.set(PricingSettings.REPRICE_MODE, SettingScope.sku(jp), RepriceMode.MANUAL_CONFIRM, "manager:ops");
        f.setFx("1.0");

        f.fxSync.run("system:fx");

        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3000);
        assertThat(f.suggestions.page("PENDING", 1, 10).total()).isEqualTo(1);
    }

    @Test
    void autoModeFollowsMostSpecificPricingRule() {
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.settings.set(PricingSettings.REPRICE_MODE, SettingScope.GLOBAL, RepriceMode.AUTO, "manager:ops");
        f.jdbc.update("INSERT INTO lr_pricing_rule (scope_type, scope_id, markup_mode, markup_rate) VALUES ('GLOBAL', 0, 'MARGIN_RATE', 0.5)");
        f.jdbc.update("INSERT INTO lr_pricing_rule (scope_type, scope_id, markup_mode, markup_add_fen) VALUES ('CARD_PRODUCT', ?, 'FIXED_ADD', 1000)", c.product);
        long cn = f.sku("CN-1", c.supplier, c.product, "CN", "ESIM", c.plan2gb);
        f.prices.activateManual(cn, 2000, "manager:ops");
        f.jdbc.update("INSERT INTO lr_pricing_rule (scope_type, scope_id, markup_mode, markup_rate, rounding) VALUES ('SKU', ?, 'MARGIN_RATE', 0.5, 'TAIL_9')", cn);
        f.setFx("1.0");

        f.fxSync.run("system:fx");

        // 卡产品级 FIXED_ADD 覆盖全局：2200 + 1000
        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3200);
        // SKU 级 MARGIN_RATE 0.5 + 尾数 .9：(2×6 + 2) 港币 → 1400 分 / 0.5 = 2800 → 28.90 元
        assertThat(f.prices.active(cn).orElseThrow().priceFen()).isEqualTo(2890);
    }

    @Test
    void writeBackFailureRollsBackPriceVersion() {
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.writer.failNext();

        assertThatThrownBy(() -> f.prices.activateManual(jp, 3500, "manager:ops")).isInstanceOf(IllegalStateException.class);

        assertThat(f.prices.active(jp).orElseThrow().priceFen()).isEqualTo(3000);
        assertThat(f.count("SELECT COUNT(*) FROM lr_sku_price_version WHERE sku_binding_id = ?", jp)).isEqualTo(1);
        assertThat(f.writer.lastPrice).containsEntry("JP-1", 3000L);
    }

    @Test
    void manualActivationValidatesPrice() {
        assertThatThrownBy(() -> f.prices.activateManual(jp, 0, "manager:ops")).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.prices.activateManual(99999, 100, "manager:ops")).isInstanceOf(UserFacingException.class);
        assertThat(f.prices.active(jp)).isEmpty();
    }

    @Test
    void delistedSkuWithoutPriceIsNotRecomputed() {
        long draft = f.insertSku("JP-D", "NEW_CARD", c.supplier, c.product, f.regionId("JP"), null, "ESIM", c.plan2gb, "DRAFT");

        RepriceRun run = f.repricing.recomputeAll(RepriceTrigger.FX, "system:fx");

        assertThat(f.costVersions.latest(draft)).isEmpty();
        assertThat(run.costVersions()).isEqualTo(1);
    }
}
