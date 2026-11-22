package com.liroam.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

/** SKU 绑定的只读查询；绑定的维护（后台）不在 P1。 */
public class SkuBindings {

    private static final String SELECT = """
            SELECT id, li_goods_sku_id AS shell_sku_id, li_goods_id AS shell_goods_id, sku_kind, supplier_id, card_product_id,
                   region_id, region_group_id, form, plan_template_id, offering_id, listing_state, numbered_gate
              FROM lr_sku_binding
            """;

    private final JdbcClient jdbc;

    public SkuBindings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SkuBinding> findById(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(SkuBinding.class).optional();
    }

    public Optional<SkuBinding> findByShellSkuId(String shellSkuId) {
        return jdbc.sql(SELECT + " WHERE li_goods_sku_id = ?").param(shellSkuId).query(SkuBinding.class).optional();
    }

    /** 不在结果里的 SKU 即未绑定（普通商品，不归本站履约）。 */
    public Map<String, SkuBinding> findByShellSkuIds(Collection<String> shellSkuIds) {
        if (shellSkuIds.isEmpty()) {
            return Map.of();
        }
        List<SkuBinding> rows = jdbc.sql(SELECT + " WHERE li_goods_sku_id IN (:ids)").param("ids", shellSkuIds).query(SkuBinding.class).list();
        return rows.stream().collect(Collectors.toMap(SkuBinding::shellSkuId, Function.identity()));
    }
}
