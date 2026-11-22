package com.liroam.pricing.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.snapshot.SnapshotRequest.SourceType;
import com.liroam.pricing.testing.PricedCatalog;
import com.liroam.pricing.testing.PricingFixture;

/** 订单行快照（§8.5、CT-PRC-03）。 */
class OrderLineSnapshotsTest {

    private PricingFixture f;
    private PricedCatalog c;
    private long jp;

    @BeforeEach
    void setUp() {
        f = PricingFixture.create("pricing_snapshot");
        c = PricedCatalog.seed(f);
        jp = f.sku("JP-1", c.supplier, c.product, "JP", "ESIM", c.plan2gb);
        f.setFx("0.9");
        f.fxSync.run("system:fx");
        f.prices.activateManual(jp, 3000, "manager:ops");
    }

    private static SnapshotRequest shop(String ref, long skuBindingId, long unitPriceFen, int qty) {
        return new SnapshotRequest(SourceType.SHOP, ref, "O-" + ref, skuBindingId, unitPriceFen, unitPriceFen * qty, qty, null);
    }

    @Test
    void recordsActiveVersionsAndUsageTextAtOrderTime() {
        long id = f.snapshots.record(shop("OI-1", jp, 3000, 2));

        OrderLineSnapshot s = f.snapshots.find(SourceType.SHOP, "OI-1").orElseThrow();
        assertThat(s.id()).isEqualTo(id);
        assertThat(s.liOrderSn()).isEqualTo("O-OI-1");
        assertThat(s.supplierId()).isEqualTo(c.supplier);
        assertThat(s.cardProductId()).isEqualTo(c.product);
        assertThat(s.regionId()).isEqualTo(f.regionId("JP"));
        assertThat(s.regionGroupId()).isNull();
        assertThat(s.tariffSheetId()).isEqualTo(c.sheet);
        assertThat(s.fxRateVersionId()).isEqualTo(f.fxRates.active().orElseThrow().id());
        assertThat(s.costVersionId()).isEqualTo(f.costVersions.latest(jp).orElseThrow().id());
        assertThat(s.priceVersionId()).isEqualTo(f.prices.active(jp).orElseThrow().priceVersionId());
        assertThat(s.unitPriceFen()).isEqualTo(3000);
        assertThat(s.paidFen()).isEqualTo(6000);
        assertThat(s.qty()).isEqualTo(2);
        // 行合计成本，与 paid_fen 同口径
        assertThat(s.costCnyFen()).isEqualTo(3960);
        assertThat(s.usageRegionText()).isEqualTo(f.describer.describe(jp).fullText());
        assertThat(s.listPriceFen()).isEqualTo(3000);
        assertThat(s.paidDiffersFromListPrice()).isFalse();
    }

    @Test
    void sameSourceLineIsIdempotentAndKeepsFirstContent() {
        long first = f.snapshots.record(shop("OI-1", jp, 3000, 1));
        long second = f.snapshots.record(shop("OI-1", jp, 1, 9));

        assertThat(second).isEqualTo(first);
        assertThat(f.count("SELECT COUNT(*) FROM lr_order_line_snapshot")).isEqualTo(1);
        assertThat(f.snapshots.find(SourceType.SHOP, "OI-1").orElseThrow().qty()).isEqualTo(1);
        assertThat(f.snapshots.record(new SnapshotRequest(SourceType.CHANNEL, "OI-1", null, jp, 2800, 2800, 1, null))).isNotEqualTo(first);
    }

    @Test
    void ct_prc_03_repricingDoesNotChangeExistingSnapshots() {
        f.snapshots.record(shop("OI-1", jp, 3000, 1));
        OrderLineSnapshot before = f.snapshots.find(SourceType.SHOP, "OI-1").orElseThrow();

        f.setFx("1.0");
        f.fxSync.run("system:fx");
        f.prices.confirmSuggestion(f.suggestions.page("PENDING", 1, 10).records().get(0).id(), "manager:ops");
        f.snapshots.record(shop("OI-2", jp, 3333, 1));

        OrderLineSnapshot after = f.snapshots.find(SourceType.SHOP, "OI-1").orElseThrow();
        assertThat(after).usingRecursiveComparison().isEqualTo(before);
        OrderLineSnapshot later = f.snapshots.find(SourceType.SHOP, "OI-2").orElseThrow();
        assertThat(later.priceVersionId()).isNotEqualTo(before.priceVersionId());
        assertThat(later.costCnyFen()).isEqualTo(2200);
        // 毛利按快照算：旧单 3000 - 1980
        assertThat(before.paidFen() - before.costCnyFen()).isEqualTo(1020);
    }

    @Test
    void shellPaidPriceWinsWhenDifferentFromActivePrice() {
        f.snapshots.record(shop("OI-1", jp, 2500, 1));

        OrderLineSnapshot s = f.snapshots.find(SourceType.SHOP, "OI-1").orElseThrow();
        assertThat(s.unitPriceFen()).isEqualTo(2500);
        assertThat(s.listPriceFen()).isEqualTo(3000);
        assertThat(s.paidDiffersFromListPrice()).isTrue();
    }

    @Test
    void topUpLineKeepsCardAssetAndUnboundSkuIsRejected() {
        long topUp = f.insertSku("JP-T", "TOP_UP", c.supplier, c.product, f.regionId("JP"), null, "SOFT", c.plan2gb, "LISTED");
        f.snapshots.record(new SnapshotRequest(SourceType.TOPUP, "OI-T", "O-T", topUp, 2000, 2000, 1, 77L));

        OrderLineSnapshot s = f.snapshots.find(SourceType.TOPUP, "OI-T").orElseThrow();
        assertThat(s.topupCardAssetId()).isEqualTo(77L);
        assertThat(s.priceVersionId()).isNull();
        assertThat(s.listPriceFen()).isNull();
        assertThat(s.paidDiffersFromListPrice()).isFalse();
        assertThatThrownBy(() -> f.snapshots.record(shop("OI-X", 99999, 100, 1))).isInstanceOf(UserFacingException.class);
    }
}
