package com.liroam.catalog;

import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 启动时核对：每个启用的供应商都有对应适配器 Bean，否则启动失败。
 * 生产环境装了 {@code @Simulated} 适配器由 {@code ProductionSafetyGate} 拦截，这里不重复判断。
 */
public class SupplierWiringCheck implements SmartInitializingSingleton {

    private final SupplierDirectory suppliers;
    private final SupplierAdapterRegistry adapters;

    public SupplierWiringCheck(SupplierDirectory suppliers, SupplierAdapterRegistry adapters) {
        this.suppliers = suppliers;
        this.adapters = adapters;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> missing = suppliers.enabled().stream()
                .filter(s -> adapters.find(s.adapterKey()).isEmpty())
                .map(s -> s.code() + "→" + s.adapterKey())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("启用的供应商缺少适配器：" + missing + "；装配适配器或在 lr_supplier 停用该供应商");
        }
    }
}
