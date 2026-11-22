package com.liroam.catalog;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

public class SupplierDirectory {

    private static final String SELECT = "SELECT id, code, name, adapter_key, enabled, listing_enabled, rate_limit_per_min FROM lr_supplier";

    private final JdbcClient jdbc;

    public SupplierDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Supplier> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Supplier.class).optional();
    }

    public List<Supplier> enabled() {
        return jdbc.sql(SELECT + " WHERE enabled = 1 ORDER BY id").query(Supplier.class).list();
    }
}
