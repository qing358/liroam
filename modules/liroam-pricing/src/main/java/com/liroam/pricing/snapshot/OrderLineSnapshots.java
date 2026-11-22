package com.liroam.pricing.snapshot;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.fx.FxRateVersion;
import com.liroam.pricing.fx.FxRates;
import com.liroam.pricing.price.ActivePrice;
import com.liroam.pricing.price.CostVersion;
import com.liroam.pricing.price.CostVersions;
import com.liroam.pricing.price.PriceVersions;
import com.liroam.pricing.region.UsageRegionDescriber;

/**
 * 订单行快照（§8.5、CT-PRC-03）。下单落库时调用（自营 H2、渠道 accept、加包）：记下当时 ACTIVE 的资费表、汇率、
 * 最新成本版本、ACTIVE 售价版本与使用区域说明原文。同一来源行只记第一次，之后调价不影响已有快照。
 */
public class OrderLineSnapshots {

    private final JdbcClient jdbc;
    private final SkuBindings bindings;
    private final UsageRegionDescriber describer;
    private final FxRates fxRates;
    private final CostVersions costVersions;
    private final PriceVersions prices;

    public OrderLineSnapshots(JdbcClient jdbc, SkuBindings bindings, UsageRegionDescriber describer, FxRates fxRates, CostVersions costVersions,
            PriceVersions prices) {
        this.jdbc = jdbc;
        this.bindings = bindings;
        this.describer = describer;
        this.fxRates = fxRates;
        this.costVersions = costVersions;
        this.prices = prices;
    }

    /** @return 快照 ID；同一 {@code (sourceType, sourceLineRef)} 重复调用返回第一次的 ID，内容不变 */
    public long record(SnapshotRequest req) {
        Optional<Long> existing = existingId(req);
        if (existing.isPresent()) {
            return existing.get();
        }
        SkuBinding sku = bindings.findById(req.skuBindingId())
                .orElseThrow(() -> new UserFacingException("SKU 绑定 #" + req.skuBindingId() + " 不存在，不能记订单快照"));
        Long tariffSheetId = jdbc.sql("SELECT id FROM lr_tariff_sheet WHERE supplier_id = ? AND status = 'ACTIVE'").param(sku.supplierId())
                .query(Long.class).optional().orElse(null);
        Long fxId = fxRates.active().map(FxRateVersion::id).orElse(null);
        Optional<CostVersion> cost = costVersions.latest(sku.id());
        Optional<ActivePrice> price = prices.active(sku.id());
        try {
            jdbc.sql("""
                    INSERT INTO lr_order_line_snapshot (source_type, source_line_ref, li_order_sn, sku_binding_id, supplier_id, card_product_id,
                        region_id, region_group_id, usage_region_text, tariff_sheet_id, fx_rate_version_id, cost_version_id, price_version_id,
                        unit_price_fen, paid_fen, cost_cny_fen, qty, topup_card_asset_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(req.sourceType().name(), req.sourceLineRef(), req.liOrderSn(), sku.id(), sku.supplierId(), sku.cardProductId(),
                    sku.regionId(), sku.regionGroupId(), describer.describe(sku.id()).fullText(), tariffSheetId, fxId,
                    cost.map(CostVersion::id).orElse(null), price.map(ActivePrice::priceVersionId).orElse(null), req.unitPriceFen(), req.paidFen(),
                    cost.map(c -> c.costCnyFen() * req.qty()).orElse(null), req.qty(), req.topupCardAssetId()).update();
        } catch (DuplicateKeyException concurrent) {
            // 并发同一来源行：以先写入的为准
        }
        return existingId(req).orElseThrow();
    }

    private Optional<Long> existingId(SnapshotRequest req) {
        return jdbc.sql("SELECT id FROM lr_order_line_snapshot WHERE source_type = ? AND source_line_ref = ?")
                .params(req.sourceType().name(), req.sourceLineRef()).query(Long.class).optional();
    }

    public Optional<OrderLineSnapshot> find(SnapshotRequest.SourceType sourceType, String sourceLineRef) {
        return jdbc.sql("""
                SELECT s.id, s.source_type, s.source_line_ref, s.li_order_sn, s.sku_binding_id, s.supplier_id, s.card_product_id, s.region_id,
                       s.region_group_id, s.usage_region_text, s.tariff_sheet_id, s.fx_rate_version_id, s.cost_version_id, s.price_version_id,
                       s.unit_price_fen, s.paid_fen, s.cost_cny_fen, s.qty, s.topup_card_asset_id, p.price_cny_fen AS list_price_fen
                  FROM lr_order_line_snapshot s LEFT JOIN lr_sku_price_version p ON p.id = s.price_version_id
                 WHERE s.source_type = ? AND s.source_line_ref = ?
                """).params(sourceType.name(), sourceLineRef).query(OrderLineSnapshot.class).optional();
    }
}
