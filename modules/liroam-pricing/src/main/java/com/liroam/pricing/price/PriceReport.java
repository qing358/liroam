package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.SkuBindings;
import com.liroam.common.setting.Settings;
import com.liroam.pricing.DisplayTime;
import com.liroam.pricing.fx.FxRateVersion;
import com.liroam.pricing.fx.FxRates;
import com.liroam.pricing.fx.FxSync;

/** 调价报表（P6-D）：全部 LISTED SKU 的售价、最近成本、毛利率与最低毛利率、待确认建议。只读。 */
public class PriceReport {

    private final JdbcClient jdbc;
    private final SkuBindings bindings;
    private final PricingRules rules;
    private final FxRates fxRates;
    private final FxSync fxSync;

    public PriceReport(JdbcClient jdbc, Settings settings, SkuBindings bindings, FxRates fxRates, FxSync fxSync) {
        this.jdbc = jdbc;
        this.bindings = bindings;
        this.rules = new PricingRules(jdbc, settings);
        this.fxRates = fxRates;
        this.fxSync = fxSync;
    }

    private record Stored(long skuBindingId, String liGoodsSkuId, String supplierCode, String cardProduct, String regionText, String form,
            String planName, Long priceFen, Long costCnyFen, Long pendingSuggestionId, Long suggestedPriceFen) {
    }

    public PriceReportView build() {
        List<PriceReportRow> rows = jdbc.sql("""
                SELECT b.id AS sku_binding_id, b.li_goods_sku_id, s.code AS supplier_code, p.name AS card_product,
                       COALESCE(r.name_zh, g.name_zh) AS region_text, b.form, t.name AS plan_name, pv.price_cny_fen AS price_fen,
                       cv.cost_cny_fen, rs.id AS pending_suggestion_id, rs.suggested_price_fen
                  FROM lr_sku_binding b
                  JOIN lr_supplier s ON s.id = b.supplier_id
                  JOIN lr_card_product p ON p.id = b.card_product_id
                  JOIN lr_plan_template t ON t.id = b.plan_template_id
                  LEFT JOIN lr_usage_region r ON r.id = b.region_id
                  LEFT JOIN lr_region_group g ON g.id = b.region_group_id
                  LEFT JOIN lr_sku_price_version pv ON pv.sku_binding_id = b.id AND pv.status = 'ACTIVE'
                  LEFT JOIN lr_sku_cost_version cv ON cv.id = (SELECT MAX(c.id) FROM lr_sku_cost_version c WHERE c.sku_binding_id = b.id)
                  LEFT JOIN lr_reprice_suggestion rs ON rs.sku_binding_id = b.id AND rs.status = 'PENDING'
                 WHERE b.listing_state = 'LISTED'
                 ORDER BY b.id
                """).query(Stored.class).list().stream().map(this::row).toList();
        Optional<FxRateVersion> fx = fxRates.active();
        return new PriceReportView(fx.map(FxRateVersion::rate).orElse(null), fx.map(v -> DisplayTime.format(v.fetchedAt())).orElse(null),
                fxSync.isStale(), rows);
    }

    private PriceReportRow row(Stored s) {
        BigDecimal minMargin = rules.minMargin(bindings.findById(s.skuBindingId()).orElseThrow());
        BigDecimal margin = s.priceFen() == null || s.costCnyFen() == null ? null : Repricing.margin(s.priceFen(), s.costCnyFen());
        return new PriceReportRow(s.skuBindingId(), s.liGoodsSkuId(), s.supplierCode(), s.cardProduct(), s.regionText(), s.form(), s.planName(),
                s.priceFen(), s.costCnyFen(), margin, minMargin, margin != null && margin.compareTo(minMargin) < 0, s.pendingSuggestionId(),
                s.suggestedPriceFen());
    }
}
