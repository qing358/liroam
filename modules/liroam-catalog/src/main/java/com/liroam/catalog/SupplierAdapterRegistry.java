package com.liroam.catalog;

import java.util.Map;
import java.util.Optional;

import com.liroam.supplier.spi.SupplierAdapter;

/** 按 {@code lr_supplier.adapter_key}（= Bean 名）找适配器；上层不按供应商写分支。 */
public class SupplierAdapterRegistry {

    private final Map<String, SupplierAdapter> adaptersByKey;

    public SupplierAdapterRegistry(Map<String, SupplierAdapter> adaptersByKey) {
        this.adaptersByKey = Map.copyOf(adaptersByKey);
    }

    public Optional<SupplierAdapter> find(String adapterKey) {
        return Optional.ofNullable(adaptersByKey.get(adapterKey));
    }

    public SupplierAdapter require(Supplier supplier) {
        return find(supplier.adapterKey()).orElseThrow(() -> new IllegalStateException(
                "供应商 " + supplier.code() + " 的适配器 " + supplier.adapterKey() + " 未装配"));
    }
}
