package com.liroam.pricing.price;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.DisplayTime;
import com.liroam.pricing.Json;

/**
 * 售价版本（§8.4）。每个 SKU 至多一个 ACTIVE；激活 = 旧版置 SUPERSEDED + 新版 ACTIVE + 写回壳（H7），同一事务，写回失败整笔回滚。
 */
public class PriceVersions {

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final ShellPriceWriter writer;
    private final SkuBindings bindings;
    private final CostCalculator costs;
    private final CostVersions costVersions;
    private final AuditLog audit;

    public PriceVersions(JdbcClient jdbc, TransactionOperations tx, ShellPriceWriter writer, SkuBindings bindings, CostCalculator costs,
            CostVersions costVersions, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.writer = writer;
        this.bindings = bindings;
        this.costs = costs;
        this.costVersions = costVersions;
        this.audit = audit;
    }

    private record Stored(long skuBindingId, long id, long priceCnyFen, long costVersionId, String origin, String activatedBy,
            LocalDateTime activatedAt) {

        ActivePrice view() {
            return new ActivePrice(skuBindingId, id, priceCnyFen, costVersionId, origin, activatedBy, DisplayTime.format(UtcColumns.fromColumn(activatedAt)));
        }
    }

    public Optional<ActivePrice> active(long skuBindingId) {
        return jdbc.sql("""
                SELECT sku_binding_id, id, price_cny_fen, cost_version_id, origin, activated_by, activated_at
                  FROM lr_sku_price_version WHERE sku_binding_id = ? AND status = 'ACTIVE'
                """).param(skuBindingId).query(Stored.class).optional().map(Stored::view);
    }

    /** 运营直接定价；成本取最近成本版本，没有就按当前资费与汇率现算一版。 */
    public ActivePrice activateManual(long skuBindingId, long priceFen, String actor) {
        if (priceFen <= 0) {
            throw new UserFacingException("售价必须大于 0");
        }
        SkuBinding sku = sku(skuBindingId);
        return tx.execute(status -> {
            CostVersion cost = costVersions.latest(skuBindingId).orElseGet(() -> costVersions.record(costs.quote(skuBindingId)));
            ActivePrice before = active(skuBindingId).orElse(null);
            ActivePrice price = activate(sku, priceFen, cost.id(), "MANUAL", null, actor);
            audit.record(actor, "PRICE_MANUAL_ACTIVATE", "SKU_BINDING", String.valueOf(skuBindingId),
                    before == null ? null : Json.write(Map.of("priceFen", before.priceFen())), Json.write(Map.of("priceFen", priceFen)));
            return price;
        });
    }

    private record Suggestion(long id, long skuBindingId, long newCostVersionId, long suggestedPriceFen, String status) {
    }

    public ActivePrice confirmSuggestion(long suggestionId, String actor) {
        return tx.execute(status -> {
            Suggestion s = pendingSuggestion(suggestionId, "确认");
            ActivePrice price = activate(sku(s.skuBindingId()), s.suggestedPriceFen(), s.newCostVersionId(), "SUGGESTION", s.id(), actor);
            decide(s.id(), "CONFIRMED", actor);
            audit.record(actor, "REPRICE_CONFIRM", "REPRICE_SUGGESTION", String.valueOf(s.id()), null,
                    Json.write(Map.of("skuBindingId", s.skuBindingId(), "priceFen", s.suggestedPriceFen())));
            return price;
        });
    }

    public void rejectSuggestion(long suggestionId, String actor) {
        tx.executeWithoutResult(status -> {
            Suggestion s = pendingSuggestion(suggestionId, "驳回");
            decide(s.id(), "REJECTED", actor);
            audit.record(actor, "REPRICE_REJECT", "REPRICE_SUGGESTION", String.valueOf(s.id()), null,
                    Json.write(Map.of("skuBindingId", s.skuBindingId())));
        });
    }

    private Suggestion pendingSuggestion(long id, String verb) {
        Suggestion s = jdbc.sql("SELECT id, sku_binding_id, new_cost_version_id, suggested_price_fen, status FROM lr_reprice_suggestion WHERE id = ? FOR UPDATE")
                .param(id).query(Suggestion.class).optional().orElseThrow(() -> new UserFacingException("调价建议 #" + id + " 不存在"));
        if (!"PENDING".equals(s.status())) {
            throw new UserFacingException("调价建议 #" + id + " 已是 " + s.status() + "，不能再" + verb);
        }
        return s;
    }

    private void decide(long id, String status, String actor) {
        jdbc.sql("UPDATE lr_reprice_suggestion SET status = ?, decided_by = ?, decided_at = UTC_TIMESTAMP(3), version = version + 1, "
                + "updated_at = UTC_TIMESTAMP(3) WHERE id = ?").params(status, actor, id).update();
    }

    /** 售价另有来源时，该 SKU 还没处理的建议作废；在调用方事务内执行。 */
    void supersedePendingSuggestions(long skuBindingId) {
        jdbc.sql("UPDATE lr_reprice_suggestion SET status = 'SUPERSEDED', version = version + 1, updated_at = UTC_TIMESTAMP(3) "
                + "WHERE sku_binding_id = ? AND status = 'PENDING'").param(skuBindingId).update();
    }

    /** 调用方负责审计；在调用方事务内执行。 */
    ActivePrice activate(SkuBinding sku, long priceFen, long costVersionId, String origin, Long suggestionId, String actor) {
        return tx.execute(status -> {
            jdbc.sql("UPDATE lr_sku_price_version SET status = 'SUPERSEDED', version = version + 1, updated_at = UTC_TIMESTAMP(3) "
                    + "WHERE sku_binding_id = ? AND status = 'ACTIVE'").param(sku.id()).update();
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO lr_sku_price_version (sku_binding_id, price_cny_fen, cost_version_id, origin, suggestion_id, status, activated_at, activated_by)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(3), ?)
                    """).params(sku.id(), priceFen, costVersionId, origin, suggestionId, actor).update(key);
            writer.writeSkuPrice(sku.shellSkuId(), priceFen);
            return active(sku.id()).orElseThrow();
        });
    }

    private SkuBinding sku(long skuBindingId) {
        return bindings.findById(skuBindingId).orElseThrow(() -> new UserFacingException("SKU 绑定 #" + skuBindingId + " 不存在"));
    }
}
