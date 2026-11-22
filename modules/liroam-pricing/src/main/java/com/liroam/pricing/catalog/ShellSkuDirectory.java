package com.liroam.pricing.catalog;

import java.util.Optional;

/** 端口：按壳 SKU id 查商品（{@code li_goods_sku}），绑旅行卡时校验商品类型；由 {@code liroam-shell-bridge} 实现。 */
@FunctionalInterface
public interface ShellSkuDirectory {

    String VIRTUAL_GOODS = "VIRTUAL_GOODS";
    String PHYSICAL_GOODS = "PHYSICAL_GOODS";

    /** @param goodsType 壳商品类型（{@link #VIRTUAL_GOODS} / {@link #PHYSICAL_GOODS} / 其它） */
    record ShellSku(String skuId, String goodsId, String goodsName, String goodsType) {
    }

    Optional<ShellSku> find(String skuId);
}
