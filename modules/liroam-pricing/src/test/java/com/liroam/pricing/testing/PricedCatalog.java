package com.liroam.pricing.testing;

import static com.liroam.pricing.testing.TestRateSheet.row;

import java.io.ByteArrayInputStream;

/**
 * 定价场景的最小目录：资源 R1，日本 KDDI 10 / SoftBank 8（MAX → 10）、中国 6、香港 5（需实名）；
 * 卡费 eSIM 2、实体卡 1；2GB 套餐。汇率 0.9 时日本 eSIM 新卡成本 = (2×10 + 2) × 0.9 = 19.80 元。
 */
public final class PricedCatalog {

    public final long supplier;
    public final long product;
    public final long plan2gb;
    public final long sheet;

    private PricedCatalog(long supplier, long product, long plan2gb, long sheet) {
        this.supplier = supplier;
        this.product = product;
        this.plan2gb = plan2gb;
        this.sheet = sheet;
    }

    public static byte[] sheet(String jpKddi) {
        return new TestRateSheet().data("R1", true,
                row("亚洲", "日本", "KDDI", jpKddi, "-"),
                row("亚洲", "日本", "SoftBank", "8", "-"),
                row("亚洲", "中国", "CMCC", "6", "-"),
                row("亚洲", "香港", "CSL", "5", "实名认证"))
                .cardFee("eSIM", "2").cardFee("实体卡", "1").build();
    }

    /** 导入并激活资费表；汇率尚未同步。 */
    public static PricedCatalog seed(PricingFixture f) {
        long supplier = f.supplier("SUP_A");
        long sheet = f.tariffs.importXlsx(supplier, "r1.xlsx", new ByteArrayInputStream(sheet("10")), "manager:ops");
        f.tariffs.activate(sheet, "manager:ops");
        return new PricedCatalog(supplier, f.cardProduct(supplier, "R1"), f.planGb("2GB", 2), sheet);
    }
}
