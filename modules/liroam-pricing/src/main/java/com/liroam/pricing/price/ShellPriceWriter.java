package com.liroam.pricing.price;

/**
 * 挂接点 H7：售价版本激活时在同一事务内写回壳 {@code li_goods_sku.price}（并刷新壳的 SKU 缓存/索引）。
 * 实现在 {@code liroam-shell-bridge}；抛异常即整笔激活回滚。
 */
public interface ShellPriceWriter {

    void writeSkuPrice(String liGoodsSkuId, long priceFen);
}
