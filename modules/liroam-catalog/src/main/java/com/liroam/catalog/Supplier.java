package com.liroam.catalog;

/**
 * 上游卡商（{@code lr_supplier}）。业务代码只认 {@code code} 这类领域代号，不写厂商名。
 *
 * @param adapterKey     适配器 Bean 名
 * @param listingEnabled 该供应商的 SKU 可否上架/下单
 */
public record Supplier(long id, String code, String name, String adapterKey, boolean enabled, boolean listingEnabled, Integer rateLimitPerMin) {
}
