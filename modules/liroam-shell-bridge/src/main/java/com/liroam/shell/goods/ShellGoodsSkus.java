package com.liroam.shell.goods;

import java.util.Optional;

import com.liroam.pricing.catalog.ShellSkuDirectory;

import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.service.GoodsSkuService;

/** 绑旅行卡时查壳 SKU（{@code li_goods_sku}，含冗余的商品类型）。 */
public class ShellGoodsSkus implements ShellSkuDirectory {

    private final GoodsSkuService skus;

    public ShellGoodsSkus(GoodsSkuService skus) {
        this.skus = skus;
    }

    @Override
    public Optional<ShellSku> find(String skuId) {
        GoodsSku sku = skus.getById(skuId);
        return Optional.ofNullable(sku).map(s -> new ShellSku(s.getId(), s.getGoodsId(), s.getGoodsName(), s.getGoodsType()));
    }
}
