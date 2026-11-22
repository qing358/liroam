package com.liroam.pricing.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.LiroamApiExceptionHandler;
import com.liroam.pricing.mock.MockFxController;
import com.liroam.pricing.testing.PricedCatalog;
import com.liroam.pricing.testing.PricingFixture;

/** 管理端与买家端接口的 HTTP 形状（子计划 §3）。 */
class PricingControllersTest {

    private PricingFixture f;
    private PricedCatalog c;
    private MockMvc mvc;

    static final CurrentActor ACTOR = new CurrentActor() {
        @Override
        public String requireMemberId() {
            return "M1";
        }

        @Override
        public String requireOperator() {
            return "manager:tester";
        }
    };

    @BeforeEach
    void setUp() {
        f = PricingFixture.create("pricing_web");
        c = PricedCatalog.seed(f);
        mvc = MockMvcBuilders.standaloneSetup(
                        new PricingBuyerController(f.describer),
                        new PricingManagerController(ACTOR, f.tariffs, f.prices, f.suggestions, f.priceReport, f.batchRepricing, f.settings,
                                f.fxSync, f.fxRates),
                        new MockFxController(f.mockFx))
                .setControllerAdvice(new LiroamApiExceptionHandler())
                .build();
    }

    @Test
    void buyerUsageRegion() throws Exception {
        f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);

        mvc.perform(get("/buyer/goods/liroam/usage-region").param("skuId", "JP-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.result.headline", containsString("日本")))
                .andExpect(jsonPath("$.result.regions[0].code").value("JP"))
                .andExpect(jsonPath("$.result.regions[0].name").value("日本"))
                .andExpect(jsonPath("$.result.regions[0].continent").value("亚洲"))
                .andExpect(jsonPath("$.result.regions[0].kycRequired").value(false))
                .andExpect(jsonPath("$.result.fullText", containsString("日本")));
        mvc.perform(get("/buyer/goods/liroam/usage-region").param("skuId", "NOT-BOUND"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }

    @Test
    void manualPriceActivatesAVersionAndWritesTheShellPrice() throws Exception {
        long jp = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
        f.setFx("0.9");
        f.fxSync.run("system:fx");

        mvc.perform(post("/manager/liroam/sku-bindings/{id}/price", jp).contentType(MediaType.APPLICATION_JSON).content("{\"priceFen\":3990}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.skuBindingId").value(jp))
                .andExpect(jsonPath("$.result.priceFen").value(3990));
        org.assertj.core.api.Assertions.assertThat(f.writer.lastPrice).containsEntry("JP-1", 3990L);

        mvc.perform(post("/manager/liroam/sku-bindings/{id}/price", jp).contentType(MediaType.APPLICATION_JSON).content("{\"priceFen\":0}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("售价")));
        org.assertj.core.api.Assertions.assertThat(f.writer.lastPrice).containsEntry("JP-1", 3990L);
    }

    @Test
    void repriceSuggestionsListConfirmReject() throws Exception {
        long jp = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
        long cn = f.sku("CN-1", c.supplier, c.product, "CN", "ESIM", c.plan2gb);
        f.setFx("0.9");
        f.fxSync.run("system:fx");
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.prices.activateManual(cn, 2000, "manager:ops");
        f.setFx("1.0");
        f.fxSync.run("system:fx");
        long jpSuggestion = f.suggestions.page("PENDING", 1, 10).records().stream().filter(s -> s.skuBindingId() == jp).findFirst().orElseThrow().id();
        long cnSuggestion = f.suggestions.page("PENDING", 1, 10).records().stream().filter(s -> s.skuBindingId() == cn).findFirst().orElseThrow().id();

        mvc.perform(get("/manager/liroam/reprice-suggestions").param("status", "PENDING").param("pageNumber", "1").param("pageSize", "1"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.total").value(2))
                .andExpect(jsonPath("$.result.current").value(1))
                .andExpect(jsonPath("$.result.size").value(1))
                .andExpect(jsonPath("$.result.records", hasSize(1)))
                .andExpect(jsonPath("$.result.records[0].skuBindingId").isNumber())
                .andExpect(jsonPath("$.result.records[0].liGoodsSkuId").isString())
                .andExpect(jsonPath("$.result.records[0].triggerKind").value("FX"))
                .andExpect(jsonPath("$.result.records[0].currentPriceFen").isNumber())
                .andExpect(jsonPath("$.result.records[0].suggestedPriceFen").isNumber())
                .andExpect(jsonPath("$.result.records[0].marginBefore").isNumber())
                .andExpect(jsonPath("$.result.records[0].marginAfter").isNumber())
                .andExpect(jsonPath("$.result.records[0].status").value("PENDING"))
                .andExpect(jsonPath("$.result.records[0].createdText").isString());

        mvc.perform(post("/manager/liroam/reprice-suggestions/{id}/confirm", jpSuggestion))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.priceFen").value(3333))
                .andExpect(jsonPath("$.result.skuBindingId").value(jp));
        mvc.perform(post("/manager/liroam/reprice-suggestions/{id}/reject", cnSuggestion))
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/manager/liroam/reprice-suggestions/{id}/confirm", cnSuggestion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("建议")));
        mvc.perform(get("/manager/liroam/reprice-suggestions"))
                .andExpect(jsonPath("$.result.total").value(2));
        org.assertj.core.api.Assertions.assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE actor = 'manager:tester'")).isEqualTo(2);
    }

    @Test
    void repriceReportBatchDecisionsAndBatchAdjust() throws Exception {
        long jp = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
        long cn = f.sku("CN-1", c.supplier, c.product, "CN", "ESIM", c.plan2gb);
        f.setFx("0.9");
        f.fxSync.run("system:fx");
        f.prices.activateManual(jp, 3000, "manager:ops");
        f.prices.activateManual(cn, 1300, "manager:ops");

        mvc.perform(get("/manager/liroam/reprice-report"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.fxRate").value(0.9))
                .andExpect(jsonPath("$.result.fxFetchedText").isString())
                .andExpect(jsonPath("$.result.fxStale").value(false))
                .andExpect(jsonPath("$.result.rows", hasSize(2)))
                .andExpect(jsonPath("$.result.rows[0].skuBindingId").value(jp))
                .andExpect(jsonPath("$.result.rows[0].liGoodsSkuId").value("JP-1"))
                .andExpect(jsonPath("$.result.rows[0].supplierCode").value("SUP_A"))
                .andExpect(jsonPath("$.result.rows[0].cardProduct").isString())
                .andExpect(jsonPath("$.result.rows[0].regionText").value("日本"))
                .andExpect(jsonPath("$.result.rows[0].form").value("ESIM"))
                .andExpect(jsonPath("$.result.rows[0].planName").value("2GB"))
                .andExpect(jsonPath("$.result.rows[0].priceFen").value(3000))
                .andExpect(jsonPath("$.result.rows[0].costCnyFen").value(1980))
                .andExpect(jsonPath("$.result.rows[0].marginRate").value(0.34))
                .andExpect(jsonPath("$.result.rows[0].minMarginRate").value(0.10))
                .andExpect(jsonPath("$.result.rows[0].belowMin").value(false))
                .andExpect(jsonPath("$.result.rows[0].pendingSuggestionId").value(nullValue()))
                .andExpect(jsonPath("$.result.rows[1].belowMin").value(true));

        mvc.perform(post("/manager/liroam/reprice-batch/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuBindingIds\":[" + jp + "],\"mode\":\"PERCENT\",\"value\":0.1}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.belowMinCount").value(0))
                .andExpect(jsonPath("$.result.rows[0].liGoodsSkuId").value("JP-1"))
                .andExpect(jsonPath("$.result.rows[0].newPriceFen").value(3300))
                .andExpect(jsonPath("$.result.rows[0].marginAfter").value(0.4));
        mvc.perform(post("/manager/liroam/reprice-batch/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuBindingIds\":[" + jp + "," + cn + "],\"mode\":\"PERCENT\",\"value\":0.01}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("最低毛利率")));
        mvc.perform(post("/manager/liroam/reprice-batch/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuBindingIds\":[" + jp + "],\"mode\":\"PERCENT\",\"value\":0.1}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.rows[0].newPriceFen").value(3300));
        org.assertj.core.api.Assertions.assertThat(f.writer.lastPrice).containsEntry("JP-1", 3300L);

        f.setFx("1.0");
        f.fxSync.run("system:fx");
        long jpSuggestion = f.suggestions.page("PENDING", 1, 10).records().stream().filter(s -> s.skuBindingId() == jp).findFirst().orElseThrow().id();
        long cnSuggestion = f.suggestions.page("PENDING", 1, 10).records().stream().filter(s -> s.skuBindingId() == cn).findFirst().orElseThrow().id();
        mvc.perform(post("/manager/liroam/reprice-suggestions/batch-reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + cnSuggestion + "]}"))
                .andExpect(jsonPath("$.result.done[0]").value(cnSuggestion));
        mvc.perform(post("/manager/liroam/reprice-suggestions/batch-confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + jpSuggestion + "," + cnSuggestion + "]}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.done", hasSize(1)))
                .andExpect(jsonPath("$.result.done[0]").value(jpSuggestion))
                .andExpect(jsonPath("$.result.failed[0].id").value(cnSuggestion))
                .andExpect(jsonPath("$.result.failed[0].message", containsString("REJECTED")));
    }

    @Test
    void pricingSettingsReadAndPartialUpdate() throws Exception {
        mvc.perform(get("/manager/liroam/settings/pricing"))
                .andExpect(jsonPath("$.result.repriceMode").value("MANUAL_CONFIRM"))
                .andExpect(jsonPath("$.result.repriceThresholdRate").value(0.03))
                .andExpect(jsonPath("$.result.minMarginRate").value(0.10))
                .andExpect(jsonPath("$.result.fxSource").value("mock"))
                .andExpect(jsonPath("$.result.fxStaleHours").value(48));

        mvc.perform(put("/manager/liroam/settings/pricing").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repriceMode\":\"AUTO\",\"minMarginRate\":0.15}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.repriceMode").value("AUTO"))
                .andExpect(jsonPath("$.result.minMarginRate").value(0.15))
                .andExpect(jsonPath("$.result.repriceThresholdRate").value(0.03));

        mvc.perform(put("/manager/liroam/settings/pricing").contentType(MediaType.APPLICATION_JSON).content("{\"repriceThresholdRate\":1.5}"))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(put("/manager/liroam/settings/pricing").contentType(MediaType.APPLICATION_JSON).content("{\"fxStaleHours\":0}"))
                .andExpect(jsonPath("$.success").value(false));
        org.assertj.core.api.Assertions.assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'SETTING_SET' AND actor = 'manager:tester'"))
                .isEqualTo(2);
    }

    @Test
    void fxSyncRatesAndDevMock() throws Exception {
        mvc.perform(post("/manager/liroam/dev/fx-mock").contentType(MediaType.APPLICATION_JSON).content("{\"rate\":0.93}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.rate").value(0.93));
        mvc.perform(post("/manager/liroam/dev/fx-mock").contentType(MediaType.APPLICATION_JSON).content("{\"rate\":-1}"))
                .andExpect(jsonPath("$.success").value(false));

        mvc.perform(post("/manager/liroam/fx/sync"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.ok").value(true))
                .andExpect(jsonPath("$.result.rate").value(0.93))
                .andExpect(jsonPath("$.result.sourceKey").value("mock"))
                .andExpect(jsonPath("$.result.versionId").isNumber());

        mvc.perform(get("/manager/liroam/fx/rates").param("limit", "5"))
                .andExpect(jsonPath("$.result", hasSize(1)))
                .andExpect(jsonPath("$.result[0].rate").value(0.93))
                .andExpect(jsonPath("$.result[0].sourceKey").value("mock"))
                .andExpect(jsonPath("$.result[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.result[0].fetchedText").isString());
    }

    @Test
    void tariffSheetUploadListActivateAndRegions() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "r1c.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", PricedCatalog.sheet("12"));

        mvc.perform(multipart("/manager/liroam/tariff-sheets").file(file).param("supplierId", String.valueOf(c.supplier)))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.sheetId").isNumber())
                .andExpect(jsonPath("$.result.status").value("DRAFT"))
                .andExpect(jsonPath("$.result.lineCount").value(6))
                .andExpect(jsonPath("$.result.linesByKind.DATA").value(4))
                .andExpect(jsonPath("$.result.unmatchedCountries", hasSize(0)))
                .andExpect(jsonPath("$.result.resources[0].resourceCode").value("R1"))
                .andExpect(jsonPath("$.result.resources[0].regionCount").value(3));
        long sheet = f.jdbc.queryForObject("SELECT MAX(id) FROM lr_tariff_sheet", Long.class);

        mvc.perform(get("/manager/liroam/tariff-sheets").param("supplierId", String.valueOf(c.supplier)))
                .andExpect(jsonPath("$.result.total").value(2))
                .andExpect(jsonPath("$.result.records[0].id").value(sheet))
                .andExpect(jsonPath("$.result.records[0].fileName").value("r1c.xlsx"))
                .andExpect(jsonPath("$.result.records[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.result.records[0].effectiveFromText").isString())
                .andExpect(jsonPath("$.result.records[0].createdText").isString());

        mvc.perform(post("/manager/liroam/tariff-sheets/{id}/activate", sheet))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.result.status").value("ACTIVE"));
        mvc.perform(post("/manager/liroam/tariff-sheets/{id}/activate", sheet))
                .andExpect(jsonPath("$.success").value(false));

        mvc.perform(get("/manager/liroam/card-products/{id}/regions", c.product))
                .andExpect(jsonPath("$.result", hasSize(3)))
                .andExpect(jsonPath("$.result[?(@.code == 'JP')].unitCostHkd").value(12.0))
                .andExpect(jsonPath("$.result[?(@.code == 'HK')].kycRequired").value(true))
                .andExpect(jsonPath("$.result[?(@.code == 'HK')].note").value("实名认证"))
                .andExpect(jsonPath("$.result[?(@.code == 'CN')].name").value("中国"))
                .andExpect(jsonPath("$.result[?(@.code == 'CN')].continent").value("亚洲"));

        mvc.perform(multipart("/manager/liroam/tariff-sheets").file(new MockMultipartFile("file", "bad.xlsx", null, new byte[] {1}))
                        .param("supplierId", String.valueOf(c.supplier)))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isString());
    }
}
