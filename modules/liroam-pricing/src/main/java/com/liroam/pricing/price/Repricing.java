package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.pricing.Json;
import com.liroam.pricing.PricingSettings;

/**
 * 新汇率或新资费激活后对全部 LISTED SKU 重算成本（§8.3–8.4）。有 ACTIVE 售价的 SKU 比较毛利率：
 * 相对售价所依据的成本版本，变动超过 {@code pricing.reprice_threshold_rate}，或新毛利率低于最低毛利率，才调价——
 * MANUAL_CONFIRM 出 PENDING 建议（同 SKU 旧 PENDING 置 SUPERSEDED），AUTO 直接出售价版本并审计。
 * <p>
 * 建议价：有加价规则按规则；没有则保持原毛利率；再抬到不低于最低毛利率。每个 SKU 一个事务，单个失败不影响其它。
 */
public class Repricing {

    private static final Log log = LogFactory.getLog(Repricing.class);

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Settings settings;
    private final SkuBindings bindings;
    private final CostCalculator costs;
    private final CostVersions costVersions;
    private final PriceVersions prices;
    private final AuditLog audit;

    public Repricing(JdbcClient jdbc, TransactionOperations tx, Settings settings, SkuBindings bindings, CostCalculator costs,
            CostVersions costVersions, PriceVersions prices, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.bindings = bindings;
        this.costs = costs;
        this.costVersions = costVersions;
        this.prices = prices;
        this.audit = audit;
    }

    private enum Outcome { COST_ONLY, SUGGESTED, AUTO_ACTIVATED }

    public RepriceRun recomputeAll(RepriceTrigger trigger, String actor) {
        List<Long> listed = jdbc.sql("SELECT id FROM lr_sku_binding WHERE listing_state = 'LISTED' ORDER BY id").query(Long.class).list();
        int cost = 0;
        int suggested = 0;
        int auto = 0;
        int skipped = 0;
        int failed = 0;
        for (long id : listed) {
            try {
                Outcome outcome = tx.execute(status -> recompute(id, trigger, actor));
                cost++;
                suggested += outcome == Outcome.SUGGESTED ? 1 : 0;
                auto += outcome == Outcome.AUTO_ACTIVATED ? 1 : 0;
            } catch (CostUnavailableException e) {
                skipped++;
                log.info("SKU 绑定 #" + id + " 跳过成本重算：" + e.getMessage());
            } catch (RuntimeException e) {
                failed++;
                log.error("SKU 绑定 #" + id + " 成本重算失败，等下次触发", e);
            }
        }
        return new RepriceRun(cost, suggested, auto, skipped, failed);
    }

    private Outcome recompute(long skuId, RepriceTrigger trigger, String actor) {
        CostVersion next = costVersions.record(costs.quote(skuId));
        Optional<ActivePrice> active = prices.active(skuId);
        if (active.isEmpty()) {
            return Outcome.COST_ONLY;
        }
        ActivePrice price = active.get();
        CostVersion base = costVersions.find(price.costVersionId()).orElseThrow();
        BigDecimal before = margin(price.priceFen(), base.costCnyFen());
        BigDecimal after = margin(price.priceFen(), next.costCnyFen());
        SkuBinding sku = bindings.findById(skuId).orElseThrow();
        SettingScope scope = SettingScope.sku(skuId);
        Optional<PricingRule> rule = rule(sku);
        BigDecimal minMargin = rule.map(PricingRule::minMarginRate).orElseGet(() -> settings.get(PricingSettings.MIN_MARGIN_RATE, scope));
        boolean moved = before.subtract(after).abs().compareTo(settings.get(PricingSettings.REPRICE_THRESHOLD_RATE, scope)) > 0;
        boolean belowMin = after.compareTo(minMargin) < 0;
        if (!moved && !belowMin) {
            return Outcome.COST_ONLY;
        }
        long target = suggestedPrice(next.costCnyFen(), before, rule, minMargin);
        if (target == price.priceFen()) {
            return Outcome.COST_ONLY;
        }
        if (settings.get(PricingSettings.REPRICE_MODE, scope) == RepriceMode.AUTO) {
            prices.activate(sku, target, next.id(), "AUTO", null, actor);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("priceFen", target);
            change.put("costCnyFen", next.costCnyFen());
            change.put("trigger", trigger);
            audit.record(actor, "PRICE_AUTO_ACTIVATE", "SKU_BINDING", String.valueOf(skuId),
                    Json.write(Map.of("priceFen", price.priceFen(), "costCnyFen", base.costCnyFen())), Json.write(change));
            return Outcome.AUTO_ACTIVATED;
        }
        jdbc.sql("UPDATE lr_reprice_suggestion SET status = 'SUPERSEDED', version = version + 1, updated_at = UTC_TIMESTAMP(3) "
                + "WHERE sku_binding_id = ? AND status = 'PENDING'").param(skuId).update();
        jdbc.sql("""
                INSERT INTO lr_reprice_suggestion (sku_binding_id, trigger_kind, old_cost_version_id, new_cost_version_id, current_price_fen,
                                                   suggested_price_fen, margin_before, margin_after, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """).params(skuId, trigger.name(), base.id(), next.id(), price.priceFen(), target, before, after).update();
        return Outcome.SUGGESTED;
    }

    static BigDecimal margin(long priceFen, long costFen) {
        return BigDecimal.valueOf(priceFen - costFen).divide(BigDecimal.valueOf(priceFen), 6, RoundingMode.HALF_UP);
    }

    static long suggestedPrice(long costFen, BigDecimal marginBefore, Optional<PricingRule> rule, BigDecimal minMargin) {
        long floor = PricingRule.divideByOneMinus(costFen, minMargin, RoundingMode.CEILING);
        long raw = rule.map(r -> r.priceFor(costFen))
                .orElseGet(() -> marginBefore.compareTo(BigDecimal.ONE) < 0 ? PricingRule.divideByOneMinus(costFen, marginBefore, RoundingMode.HALF_UP) : floor);
        long price = Math.max(raw, floor);
        return rule.map(r -> r.round(price)).orElse(price);
    }

    private Optional<PricingRule> rule(SkuBinding sku) {
        return jdbc.sql("""
                SELECT scope_type, markup_mode, markup_rate, markup_add_fen, rounding, min_margin_rate FROM lr_pricing_rule
                 WHERE effective_from <= UTC_TIMESTAMP(3)
                   AND ((scope_type = 'SKU' AND scope_id = ?) OR (scope_type = 'CARD_PRODUCT' AND scope_id = ?)
                        OR (scope_type = 'SUPPLIER' AND scope_id = ?) OR scope_type = 'GLOBAL')
                 ORDER BY FIELD(scope_type, 'SKU', 'CARD_PRODUCT', 'SUPPLIER', 'GLOBAL'), effective_from DESC, id DESC
                 LIMIT 1
                """).params(sku.id(), sku.cardProductId(), sku.supplierId()).query(PricingRule.class).optional();
    }
}
