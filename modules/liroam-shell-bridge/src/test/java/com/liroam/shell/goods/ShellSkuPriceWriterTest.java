package com.liroam.shell.goods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.modules.goods.entity.dos.Goods;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.service.GoodsService;
import cn.lili.modules.goods.service.GoodsSkuService;

/** H7：售价写回经壳 {@code GoodsSkuService.update(GoodsSku)}（落库 + 刷新 SKU 缓存），商品最低价同步。 */
class ShellSkuPriceWriterTest {

    @SuppressWarnings("unchecked")
    private final Cache<Object> cache = mock(Cache.class);
    private final GoodsSkuService skus = mock(GoodsSkuService.class);
    private final GoodsService goods = mock(GoodsService.class);
    private final ShellSkuPriceWriter writer = new ShellSkuPriceWriter(skus, goods, cache);

    private static GoodsSku sku(String id, double price) {
        GoodsSku s = new GoodsSku();
        s.setId(id);
        s.setGoodsId("G-1");
        s.setGoodsName("轻游 eSIM");
        s.setQuantity(99);
        s.setPrice(price);
        return s;
    }

    @Test
    void writesThroughTheShellServiceWithTheFullSkuSoTheCacheIsNotPoisoned() {
        GoodsSku current = sku("S-1", 10);
        when(skus.getById("S-1")).thenReturn(current);
        when(skus.getGoodsSkuListByGoodsId("G-1")).thenReturn(List.of(current, sku("S-2", 59.9)));
        Goods g = new Goods();
        g.setId("G-1");
        when(goods.getById("G-1")).thenReturn(g);

        writer.writeSkuPrice("S-1", 2990);

        ArgumentCaptor<GoodsSku> written = ArgumentCaptor.forClass(GoodsSku.class);
        verify(skus).update(written.capture());
        assertThat(written.getValue().getPrice()).isEqualTo(29.9);
        assertThat(written.getValue().getGoodsName()).isEqualTo("轻游 eSIM");
        assertThat(written.getValue().getQuantity()).isEqualTo(99);
        ArgumentCaptor<Goods> goodsWritten = ArgumentCaptor.forClass(Goods.class);
        verify(goods).updateById(goodsWritten.capture());
        assertThat(goodsWritten.getValue().getPrice()).isEqualTo(29.9);
        verify(cache).remove(CachePrefix.GOODS.getPrefix() + "G-1");
    }

    @Test
    void unknownSkuFailsSoTheActivationRollsBack() {
        assertThatThrownBy(() -> writer.writeSkuPrice("S-X", 100)).isInstanceOf(IllegalStateException.class);
        verify(skus, never()).update(any(GoodsSku.class));
    }
}
