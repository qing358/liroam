package com.liroam.pricing.region;

import static com.liroam.pricing.testing.TestRateSheet.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.liroam.pricing.testing.PricingFixture;
import com.liroam.pricing.testing.TestRateSheet;

/** 详情页使用区域说明（§7.6、§15 V2）：全部来自资费数据，单区域与区域组同一生成器。 */
class UsageRegionDescriberTest {

    private static PricingFixture f;
    private static long cn;
    private static long jp;
    private static long hk;
    private static long group;

    @BeforeAll
    static void seed() {
        f = PricingFixture.create("pricing_describer");
        long supplier = f.supplier("SUP_A");
        long sheet = f.tariffs.importXlsx(supplier, "sample.xlsx", new ByteArrayInputStream(PricingFixture.sampleSheet()), "manager:ops");
        f.tariffs.activate(sheet, "manager:ops");
        long product = f.cardProduct(supplier, "45412_C");
        long plan = f.planGb("3GB", 3);
        cn = f.sku("CN-1", supplier, product, "CN", "ESIM", plan);
        jp = f.sku("JP-1", supplier, product, "JP", "ESIM", plan);
        hk = f.sku("HK-1", supplier, product, "HK", "ESIM", plan);
        group = f.groupSku("JPHK-1", supplier, product, f.regionGroup("JP_HK", "JP", "HK"), "ESIM", plan);
    }

    @Test
    void v2_sameProductDifferentRegionsGetDifferentTextsFromTariffData() {
        UsageRegionView china = f.describer.describe(cn);
        UsageRegionView japan = f.describer.describe(jp);

        assertThat(china.fullText()).isNotEqualTo(japan.fullText());
        assertThat(china.regions()).singleElement().satisfies(r -> {
            assertThat(r.code()).isEqualTo("CN");
            assertThat(r.name()).isEqualTo("中国");
            assertThat(r.continent()).isEqualTo("亚洲");
            assertThat(r.kycRequired()).isFalse();
            assertThat(r.note()).contains("72小时");
        });
        assertThat(china.fullText()).contains("中国").contains("72小时");
        assertThat(japan.fullText()).contains("日本").doesNotContain("72小时");
        assertThat(china.headline()).contains("中国");
        // 产品级注意事项（禁用于物联网）两边都有
        assertThat(china.fullText()).contains("物联网");
        assertThat(japan.fullText()).contains("物联网");
    }

    @Test
    void kycRegionsAreFlaggedInHeadlineAndText() {
        UsageRegionView view = f.describer.describe(hk);

        assertThat(view.regions().get(0).kycRequired()).isTrue();
        assertThat(view.headline()).contains("实名");
        assertThat(view.fullText()).contains("需实名认证").contains("香港");
        assertThat(f.describer.describe(jp).fullText()).contains("无需实名认证");
    }

    @Test
    void regionGroupExpandsMembers() {
        UsageRegionView view = f.describer.describe(group);

        assertThat(view.regions()).extracting(UsageRegionView.Region::code).containsExactlyInAnyOrder("JP", "HK");
        assertThat(view.headline()).contains("2 个国家/地区");
        assertThat(view.fullText()).contains("日本").contains("香港").contains("需实名认证：香港");
    }

    @Test
    void shellSkuLookup() {
        assertThat(f.describer.describeShellSku("JP-1")).get().extracting(UsageRegionView::fullText).isEqualTo(f.describer.describe(jp).fullText());
        assertThat(f.describer.describeShellSku("NOPE")).isEmpty();
    }

    @Test
    void regionWithoutTariffStillDescribedByName() {
        PricingFixture g = PricingFixture.create("pricing_describer_bare");
        long supplier = g.supplier("SUP_A");
        long sheet = g.tariffs.importXlsx(supplier, "t.xlsx", new ByteArrayInputStream(new TestRateSheet()
                .data("R1", false, row("亚洲", "日本", "KDDI", "5")).build()), "manager:ops");
        g.tariffs.activate(sheet, "manager:ops");
        g.jdbc.update("INSERT INTO lr_usage_region (code, name_zh) VALUES ('KR', '韩国')");
        long sku = g.sku("KR-1", supplier, g.cardProduct(supplier, "R1"), "KR", "ESIM", g.planGb("1GB", 1));

        UsageRegionView view = g.describer.describe(sku);

        assertThat(view.regions()).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("韩国");
            assertThat(r.kycRequired()).isFalse();
        });
    }
}
