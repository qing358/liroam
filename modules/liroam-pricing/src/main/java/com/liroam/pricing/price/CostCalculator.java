package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.Json;
import com.liroam.pricing.fx.FxRateVersion;
import com.liroam.pricing.fx.FxRates;
import com.liroam.pricing.tariff.CardForms;

/**
 * SKU 成本（§8.3）= 套餐流量 GB × 区域流量单价（区域组取成员最高）+ 新卡卡费（按 eSIM / 实体卡），港币；再 × 当前汇率得人民币分。
 * 加包 SKU 不收卡费。资费取供应商 ACTIVE 资费表。
 */
public class CostCalculator {

    private static final BigDecimal BYTES_PER_GB = BigDecimal.valueOf(1024L * 1024 * 1024);

    private final JdbcClient jdbc;
    private final SkuBindings bindings;
    private final FxRates fxRates;

    public CostCalculator(JdbcClient jdbc, SkuBindings bindings, FxRates fxRates) {
        this.jdbc = jdbc;
        this.bindings = bindings;
        this.fxRates = fxRates;
    }

    private record Plan(String name, Long dataQuotaBytes) {
    }

    private record RegionCost(String code, String name, BigDecimal unitCost) {
    }

    private record CardFee(String itemRaw, BigDecimal unitPrice) {
    }

    public CostQuote quote(long skuBindingId) {
        SkuBinding sku = bindings.findById(skuBindingId).orElseThrow(() -> new UserFacingException("SKU 绑定 #" + skuBindingId + " 不存在"));
        FxRateVersion fx = fxRates.active().orElseThrow(() -> new CostUnavailableException("汇率尚未同步，暂时算不出成本"));
        Plan plan = jdbc.sql("SELECT name, data_quota_bytes FROM lr_plan_template WHERE id = ?").param(sku.planTemplateId()).query(Plan.class).single();
        if (plan.dataQuotaBytes() == null) {
            throw new CostUnavailableException("套餐「" + plan.name() + "」为不限流量，资费表按 GB 报价，需人工定价");
        }
        long sheetId = jdbc.sql("SELECT id FROM lr_tariff_sheet WHERE supplier_id = ? AND status = 'ACTIVE'").param(sku.supplierId())
                .query(Long.class).optional().orElseThrow(() -> new CostUnavailableException("该供应商还没有生效的资费表"));

        List<RegionCost> regions = new ArrayList<>();
        for (long regionId : regionIds(sku)) {
            regions.add(regionCost(sheetId, sku.cardProductId(), regionId));
        }
        BigDecimal unitCost = regions.stream().map(RegionCost::unitCost).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal dataGb = BigDecimal.valueOf(plan.dataQuotaBytes()).divide(BYTES_PER_GB, 6, RoundingMode.HALF_UP);
        Optional<CardFee> cardFee = sku.skuKind() == SkuBinding.SkuKind.NEW_CARD ? cardFee(sheetId, sku.form()) : Optional.empty();
        BigDecimal feeHkd = cardFee.map(CardFee::unitPrice).orElse(BigDecimal.ZERO);

        long hkdMinor = dataGb.multiply(unitCost).add(feeHkd).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long cnyFen = BigDecimal.valueOf(hkdMinor).multiply(fx.rate()).setScale(0, RoundingMode.HALF_UP).longValueExact();

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("dataGb", dataGb);
        breakdown.put("regions", regions.stream().map(r -> Map.of("code", r.code(), "unitCostHkd", r.unitCost())).toList());
        breakdown.put("unitCostHkd", unitCost);
        breakdown.put("cardFeeItem", cardFee.map(CardFee::itemRaw).orElse(null));
        breakdown.put("cardFeeHkd", feeHkd);
        breakdown.put("fxRate", fx.rate());
        return new CostQuote(skuBindingId, sheetId, fx.id(), hkdMinor, cnyFen, Json.write(breakdown));
    }

    private List<Long> regionIds(SkuBinding sku) {
        if (sku.regionId() != null) {
            return List.of(sku.regionId());
        }
        return jdbc.sql("SELECT region_id FROM lr_region_group_member WHERE group_id = ? ORDER BY region_id").param(sku.regionGroupId())
                .query(Long.class).list();
    }

    private RegionCost regionCost(long sheetId, long cardProductId, long regionId) {
        return jdbc.sql("""
                SELECT r.code, r.name_zh AS name, rt.unit_cost
                  FROM lr_usage_region r
                  LEFT JOIN lr_card_product_region cpr ON cpr.region_id = r.id AND cpr.card_product_id = ? AND cpr.active = 1
                  LEFT JOIN lr_region_tariff rt ON rt.card_product_region_id = cpr.id AND rt.sheet_id = ? AND rt.kind = 'DATA'
                 WHERE r.id = ?
                """).params(cardProductId, sheetId, regionId).query(RegionCost.class).optional()
                .filter(c -> c.unitCost() != null)
                .orElseThrow(() -> new CostUnavailableException("「" + regionName(regionId) + "」不在当前资费表的可用区域里，算不出流量成本"));
    }

    private String regionName(long regionId) {
        return jdbc.sql("SELECT name_zh FROM lr_usage_region WHERE id = ?").param(regionId).query(String.class).optional()
                .orElse("区域 #" + regionId);
    }

    private Optional<CardFee> cardFee(long sheetId, String form) {
        return jdbc.sql("SELECT item_raw, unit_price FROM lr_tariff_line WHERE sheet_id = ? AND kind = 'CARD_FEE' ORDER BY id")
                .param(sheetId).query(CardFee.class).list().stream()
                .filter(f -> CardForms.formOf(f.itemRaw()).filter(form::equals).isPresent())
                .findFirst();
    }
}
