package com.liroam.shell.goods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.liroam.pricing.catalog.ShellSkuDirectory;
import com.liroam.pricing.catalog.ShellSkuDirectory.ShellSku;

import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.goods.service.GoodsSkuService;

/** 绑旅行卡查壳 SKU：带回商品 id、名称与壳商品类型；不存在为空。 */
class ShellGoodsSkusTest {

    @Test
    void mapsShellSkuAndMissingIsEmpty() {
        GoodsSkuService skus = mock(GoodsSkuService.class);
        GoodsSku sku = new GoodsSku();
        sku.setId("S1");
        sku.setGoodsId("G1");
        sku.setGoodsName("日本卡");
        sku.setGoodsType("PHYSICAL_GOODS");
        when(skus.getById("S1")).thenReturn(sku);
        ShellGoodsSkus directory = new ShellGoodsSkus(skus);

        assertThat(directory.find("S1")).contains(new ShellSku("S1", "G1", "日本卡", ShellSkuDirectory.PHYSICAL_GOODS));
        assertThat(directory.find("S2")).isEmpty();
    }
}
