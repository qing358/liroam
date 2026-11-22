package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.SkuBinding;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.pricing.PricingSettings;

/** SKU 生效的加价规则与最低毛利率：调价重算、调价报表、批量改价同一口径。 */
final class PricingRules {

    private final JdbcClient jdbc;
    private final Settings settings;

    PricingRules(JdbcClient jdbc, Settings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    Optional<PricingRule> forSku(SkuBinding sku) {
        return jdbc.sql("""
                SELECT scope_type, markup_mode, markup_rate, markup_add_fen, rounding, min_margin_rate FROM lr_pricing_rule
                 WHERE effective_from <= UTC_TIMESTAMP(3)
                   AND ((scope_type = 'SKU' AND scope_id = ?) OR (scope_type = 'CARD_PRODUCT' AND scope_id = ?)
                        OR (scope_type = 'SUPPLIER' AND scope_id = ?) OR scope_type = 'GLOBAL')
                 ORDER BY FIELD(scope_type, 'SKU', 'CARD_PRODUCT', 'SUPPLIER', 'GLOBAL'), effective_from DESC, id DESC
                 LIMIT 1
                """).params(sku.id(), sku.cardProductId(), sku.supplierId()).query(PricingRule.class).optional();
    }

    /** 规则里写了最低毛利率用规则的，否则用 SKU 作用域的 {@code pricing.min_margin_rate}。 */
    BigDecimal minMargin(SkuBinding sku, Optional<PricingRule> rule) {
        return rule.map(PricingRule::minMarginRate).orElseGet(() -> settings.get(PricingSettings.MIN_MARGIN_RATE, SettingScope.sku(sku.id())));
    }

    BigDecimal minMargin(SkuBinding sku) {
        return minMargin(sku, forSku(sku));
    }
}
