package com.liroam.pricing.tariff;

import static com.liroam.pricing.testing.TestRateSheet.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.setting.SettingScope;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.PricingSettings;
import com.liroam.pricing.testing.PricingFixture;
import com.liroam.pricing.testing.TestRateSheet;

/** 资费导入与激活（domain-design §8.1、§15 V1），用 docs/media 下的真实样例表。 */
class TariffImportsTest {

    private PricingFixture f;
    private long supplier;

    @BeforeEach
    void setUp() {
        f = PricingFixture.create("pricing_tariff");
        supplier = f.supplier("SUP_A");
    }

    private long importSample() {
        return f.tariffs.importXlsx(supplier, "sample.xlsx", new ByteArrayInputStream(PricingFixture.sampleSheet()), "manager:ops");
    }

    @Test
    void v1_sampleSheetYieldsAllCountriesOfResourceWithKycAndRestrictions() {
        long sheet = importSample();
        f.tariffs.activate(sheet, "manager:ops");

        ImportReport report = f.tariffs.report(sheet);
        assertThat(report.status()).isEqualTo("ACTIVE");
        assertThat(report.unmatchedCountries()).isEmpty();
        assertThat(report.linesByKind()).containsEntry("DATA", 441).containsEntry("VOICE", 208).containsEntry("SMS", 166).containsEntry("CARD_FEE", 5);
        Map<String, Integer> regionsByResource = report.resources().stream()
                .collect(Collectors.toMap(ImportReport.ResourceSummary::resourceCode, ImportReport.ResourceSummary::regionCount));
        assertThat(regionsByResource).containsExactlyInAnyOrderEntriesOf(Map.of("45400_T", 106, "45407", 33, "45412_C", 82, "45412_H", 47));

        long product = f.cardProduct(supplier, "45412_C");
        List<CardProductRegionRow> regions = f.tariffs.cardProductRegions(product);
        assertThat(regions).hasSize(82).allSatisfy(r -> assertThat(r.unitCostHkd()).isPositive());
        Map<String, CardProductRegionRow> byCode = regions.stream().collect(Collectors.toMap(CardProductRegionRow::code, r -> r));
        assertThat(byCode.get("HK").kycRequired()).isTrue();
        assertThat(byCode.get("TW").kycRequired()).isTrue();
        assertThat(byCode.get("HK").name()).isEqualTo("香港");
        assertThat(byCode.get("JP").kycRequired()).isFalse();
        assertThat(byCode.get("CN").note()).contains("中国内地").contains("72小时");
        assertThat(byCode.get("JP").note()).isNullOrEmpty();
        assertThat(byCode.get("JP").continent()).isEqualTo("亚洲");
        // 日本 KDDI 5.8824 / SoftBank 7.0588，默认 MAX
        assertThat(byCode.get("JP").unitCostHkd()).isEqualByComparingTo("7.0588");
        assertThat(f.count("SELECT COUNT(*) FROM lr_card_product WHERE supplier_id = ?", supplier)).isEqualTo(4);
        assertThat(f.jdbc.queryForObject("SELECT kyc_policy FROM lr_card_product WHERE id = ?", String.class, product)).isEqualTo("CONDITIONAL");
        assertThat(f.jdbc.queryForObject("SELECT allowed_forms FROM lr_card_product WHERE id = ?", String.class, product)).isEqualTo("ESIM,PHYSICAL");
        assertThat(f.jdbc.queryForObject("SELECT home_location FROM lr_card_product WHERE id = ?", String.class, product)).isEqualTo("HK");
        assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action IN ('TARIFF_IMPORT', 'TARIFF_ACTIVATE')")).isEqualTo(2);
    }

    @Test
    void draftDoesNotTouchStorefrontUntilActivated() {
        long sheet = importSample();

        assertThat(f.tariffs.report(sheet).status()).isEqualTo("DRAFT");
        assertThat(f.tariffs.cardProductRegions(f.cardProduct(supplier, "45412_C"))).isEmpty();
    }

    @Test
    void sameFileTwiceReturnsOriginalSheet() {
        long first = importSample();
        long second = importSample();

        assertThat(second).isEqualTo(first);
        assertThat(f.count("SELECT COUNT(*) FROM lr_tariff_sheet")).isEqualTo(1);
        assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'TARIFF_IMPORT'")).isEqualTo(1);
    }

    @Test
    void sameFileForAnotherSupplierIsSeparateSheet() {
        long other = f.supplier("SUP_B");
        long first = importSample();
        long second = f.tariffs.importXlsx(other, "sample.xlsx", new ByteArrayInputStream(PricingFixture.sampleSheet()), "manager:ops");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void unmatchedCountriesAreReportedAndKeptRaw() {
        byte[] xlsx = new TestRateSheet().data("R1", false,
                row("亚洲", "日本", "KDDI", "5"),
                row("火星", "火星殖民地", "Ares", "99")).build();

        long sheet = f.tariffs.importXlsx(supplier, "t.xlsx", new ByteArrayInputStream(xlsx), "manager:ops");

        ImportReport report = f.tariffs.report(sheet);
        assertThat(report.unmatchedCountries()).containsExactly("火星殖民地");
        assertThat(report.resources()).singleElement().satisfies(r -> assertThat(r.regionCount()).isEqualTo(1));
        assertThat(f.jdbc.queryForObject("SELECT continent_raw FROM lr_tariff_line WHERE country_raw = '火星殖民地'", String.class)).isEqualTo("火星");
        assertThat(f.count("SELECT COUNT(*) FROM lr_tariff_line WHERE country_raw = '火星殖民地' AND region_id IS NULL")).isEqualTo(1);
    }

    @Test
    void carrierRuleAvgFromSettings() {
        f.settings.set(PricingSettings.TARIFF_CARRIER_RULE, SettingScope.supplier(supplier), CarrierRule.AVG, "manager:fin");
        byte[] xlsx = new TestRateSheet().data("R1", false,
                row("亚洲", "日本", "DOCOMO", "113.2424"),
                row("亚洲", "日本", "KDDI", "8.4329"),
                row("亚洲", "日本", "SoftBank", "16.8659")).build();

        long sheet = f.tariffs.importXlsx(supplier, "t.xlsx", new ByteArrayInputStream(xlsx), "manager:ops");
        f.tariffs.activate(sheet, "manager:ops");

        CardProductRegionRow jp = f.tariffs.cardProductRegions(f.cardProduct(supplier, "R1")).get(0);
        assertThat(jp.unitCostHkd()).isEqualByComparingTo(new BigDecimal("46.180400"));
        assertThat(f.jdbc.queryForObject("SELECT carrier_rule FROM lr_region_tariff", String.class)).isEqualTo("AVG");
    }

    @Test
    void activatingNewSheetSupersedesOldAndDropsRegionsNoLongerListed() {
        long first = f.tariffs.importXlsx(supplier, "a.xlsx", new ByteArrayInputStream(new TestRateSheet().data("R1", false,
                row("亚洲", "日本", "KDDI", "5"), row("亚洲", "韩国", "SKT", "6")).build()), "manager:ops");
        f.tariffs.activate(first, "manager:ops");
        long second = f.tariffs.importXlsx(supplier, "b.xlsx", new ByteArrayInputStream(new TestRateSheet().data("R1", false,
                row("亚洲", "日本", "KDDI", "4")).build()), "manager:ops");

        f.tariffs.activate(second, "manager:ops");

        assertThat(f.tariffs.report(first).status()).isEqualTo("SUPERSEDED");
        assertThat(f.tariffs.report(second).status()).isEqualTo("ACTIVE");
        List<CardProductRegionRow> regions = f.tariffs.cardProductRegions(f.cardProduct(supplier, "R1"));
        assertThat(regions).extracting(CardProductRegionRow::code).containsExactly("JP");
        assertThat(regions.get(0).unitCostHkd()).isEqualByComparingTo("4");
        assertThatThrownBy(() -> f.tariffs.activate(first, "manager:ops")).isInstanceOf(UserFacingException.class);
    }

    @Test
    void rowRemarksAndFootnotesUnionIntoKycAndNotes() {
        byte[] xlsx = new TestRateSheet().data("R1", true,
                row("亚洲", "菲律宾", "Globe", "8", "实名认证 (i)"),
                row("亚洲", "香港", "CSL", "5", "-"),
                row("亚洲", "中国", "CMCC", "6", "-"),
                row("亚洲", "日本", "KDDI", "5", "-"))
                .notes("(i) 实名认证:\nR1在香港使用服务前，必须完成实名登记。\n\n(ii) R1 在中国内地使用特殊要求:\n满72小时后方可使用。\n\n(iii) R1 禁止用于物联网场景")
                .build();

        long sheet = f.tariffs.importXlsx(supplier, "t.xlsx", new ByteArrayInputStream(xlsx), "manager:ops");
        f.tariffs.activate(sheet, "manager:ops");

        Map<String, CardProductRegionRow> byCode = f.tariffs.cardProductRegions(f.cardProduct(supplier, "R1")).stream()
                .collect(Collectors.toMap(CardProductRegionRow::code, r -> r));
        assertThat(byCode.get("PH").kycRequired()).isTrue();
        assertThat(byCode.get("PH").note()).contains("实名认证 (i)").contains("必须完成实名登记");
        assertThat(byCode.get("HK").kycRequired()).isTrue();
        assertThat(byCode.get("CN").kycRequired()).isFalse();
        assertThat(byCode.get("CN").note()).contains("72小时");
        assertThat(byCode.get("JP").note()).isNullOrEmpty();
        assertThat(f.jdbc.queryForObject("SELECT CAST(constraints_json AS CHAR) FROM lr_card_product WHERE card_type_code = 'R1'", String.class))
                .contains("禁止用于物联网");
    }

    @Test
    void cardFeesAndVoiceAreImported() {
        byte[] xlsx = new TestRateSheet().data("R1", false, row("亚洲", "日本", "KDDI", "5"))
                .voice("R1", row("日本", "KDDI", "拨打至日本", "0.3"), row("日本", "KDDI", "被叫", "0.2"))
                .cardFee("eSIM", "1.85").cardFee("实体卡", "1.2").cardFee("物联网平台费", "1200元/月").build();

        long sheet = f.tariffs.importXlsx(supplier, "t.xlsx", new ByteArrayInputStream(xlsx), "manager:ops");

        assertThat(f.tariffs.report(sheet).linesByKind()).containsEntry("VOICE", 2).containsEntry("CARD_FEE", 2);
        assertThat(f.jdbc.queryForList("SELECT direction FROM lr_region_tariff WHERE kind = 'VOICE' ORDER BY direction", String.class))
                .containsExactly("INCOMING", "LOCAL");
        assertThat(f.jdbc.queryForObject("SELECT cap_voice FROM lr_card_product WHERE card_type_code = 'R1'", Integer.class)).isEqualTo(1);
    }

    private long activate(String file, TestRateSheet sheet) {
        long id = f.tariffs.importXlsx(supplier, file, new ByteArrayInputStream(sheet.build()), "manager:ops");
        f.tariffs.activate(id, "manager:ops");
        return id;
    }

    private String allowedForms() {
        return f.jdbc.queryForObject("SELECT allowed_forms FROM lr_card_product WHERE card_type_code = 'R1'", String.class);
    }

    @Test
    void softCardFeeIsParsedAndOpsEnabledSoftSurvivesReimportOnlyWhileSheetStillPricesIt() {
        assertThat(CardForms.formOf("纯软件卡 Profile")).contains("SOFT");
        assertThat(CardForms.formOf("纯软件卡Profile")).contains("SOFT");

        activate("a.xlsx", new TestRateSheet().data("R1", false, row("亚洲", "日本", "KDDI", "5"))
                .cardFee("eSIM", "2").cardFee("纯软件卡Profile", "0.5"));
        assertThat(allowedForms()).as("软卡不自动开卖").isEqualTo("ESIM");
        assertThat(f.count("SELECT COUNT(*) FROM lr_tariff_line WHERE kind = 'CARD_FEE' AND item_raw = '纯软件卡Profile'")).isEqualTo(1);

        f.jdbc.update("UPDATE lr_card_product SET allowed_forms = 'ESIM,SOFT' WHERE card_type_code = 'R1'");
        activate("b.xlsx", new TestRateSheet().data("R1", false, row("亚洲", "日本", "KDDI", "4"))
                .cardFee("eSIM", "2").cardFee("实体卡", "1").cardFee("纯软件卡Profile", "0.6"));
        assertThat(allowedForms()).as("运营开的软卡重导保留").isEqualTo("ESIM,PHYSICAL,SOFT");

        activate("c.xlsx", new TestRateSheet().data("R1", false, row("亚洲", "日本", "KDDI", "4")).cardFee("eSIM", "2"));
        assertThat(allowedForms()).as("新表不再报软卡卡费 → 关掉").isEqualTo("ESIM");
    }

    @Test
    void rejectsNonHkdOrUnknownLayout() {
        byte[] usd = new TestRateSheet().currency("美元").data("R1", false, row("亚洲", "日本", "KDDI", "5")).build();
        assertThatThrownBy(() -> f.tariffs.importXlsx(supplier, "t.xlsx", new ByteArrayInputStream(usd), "manager:ops"))
                .isInstanceOf(UserFacingException.class).hasMessageContaining("港币");
        assertThatThrownBy(() -> f.tariffs.importXlsx(supplier, "t.xlsx", new ByteArrayInputStream(new byte[] {1, 2, 3}), "manager:ops"))
                .isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.tariffs.importXlsx(999, "t.xlsx", new ByteArrayInputStream(PricingFixture.sampleSheet()), "manager:ops"))
                .isInstanceOf(UserFacingException.class);
        assertThat(f.count("SELECT COUNT(*) FROM lr_tariff_sheet")).isZero();
    }
}
