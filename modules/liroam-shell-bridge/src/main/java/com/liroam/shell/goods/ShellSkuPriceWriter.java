package com.liroam.shell.goods;

import java.util.List;

import com.liroam.common.money.Fen;
import com.liroam.pricing.price.ShellPriceWriter;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.modules.goods.entity.dos.Goods;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.service.GoodsService;
import cn.lili.modules.goods.service.GoodsSkuService;

/**
 * H7：售价写回。{@code GoodsSkuService.update(GoodsSku)} 落库并把传入对象整个放进 SKU 缓存，所以必须先取完整 SKU 再改价；
 * 本分支的商品索引按 SKU 现读现建（{@code GoodsIndexService.buildGoodsIndex}），无需另刷。商品列表价取该商品 SKU 最低价。
 */
public class ShellSkuPriceWriter implements ShellPriceWriter {

    private final GoodsSkuService skus;
    private final GoodsService goods;
    private final Cache<?> cache;

    public ShellSkuPriceWriter(GoodsSkuService skus, GoodsService goods, Cache<?> cache) {
        this.skus = skus;
        this.goods = goods;
        this.cache = cache;
    }

    @Override
    public void writeSkuPrice(String liGoodsSkuId, long priceFen) {
        GoodsSku sku = skus.getById(liGoodsSkuId);
        if (sku == null) {
            throw new IllegalStateException("壳 SKU 不存在：" + liGoodsSkuId);
        }
        sku.setPrice(Fen.toYuan(priceFen));
        skus.update(sku);

        Goods g = goods.getById(sku.getGoodsId());
        if (g != null) {
            List<GoodsSku> siblings = skus.getGoodsSkuListByGoodsId(sku.getGoodsId());
            double min = siblings.stream().map(s -> s.getId().equals(sku.getId()) ? sku : s)
                    .mapToDouble(GoodsSku::getPrice).min().orElse(sku.getPrice());
            g.setPrice(min);
            goods.updateById(g);
            cache.remove(CachePrefix.GOODS.getPrefix() + g.getId());
        }
    }
}
