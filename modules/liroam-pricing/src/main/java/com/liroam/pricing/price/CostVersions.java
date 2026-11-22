package com.liroam.pricing.price;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

public class CostVersions {

    private static final String SELECT = "SELECT id, sku_binding_id, tariff_sheet_id, fx_rate_version_id, cost_hkd_minor, cost_cny_fen, "
            + "CAST(breakdown_json AS CHAR) AS breakdown_json FROM lr_sku_cost_version";

    private final JdbcClient jdbc;

    public CostVersions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CostVersion> latest(long skuBindingId) {
        return jdbc.sql(SELECT + " WHERE sku_binding_id = ? ORDER BY id DESC LIMIT 1").param(skuBindingId).query(CostVersion.class).optional();
    }

    public Optional<CostVersion> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(CostVersion.class).optional();
    }

    CostVersion record(CostQuote q) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_sku_cost_version (sku_binding_id, tariff_sheet_id, fx_rate_version_id, cost_hkd_minor, cost_cny_fen, breakdown_json)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSON))
                """).params(q.skuBindingId(), q.tariffSheetId(), q.fxRateVersionId(), q.costHkdMinor(), q.costCnyFen(), q.breakdownJson())
                .update(key);
        return find(key.getKey().longValue()).orElseThrow();
    }
}
