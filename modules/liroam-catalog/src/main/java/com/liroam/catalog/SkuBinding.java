package com.liroam.catalog;

/**
 * 壳 SKU → 本站卡品的绑定（{@code lr_sku_binding}）。每个 SKU 只绑一家供应商；首次上架后供应商/卡产品/使用区域锁定。
 *
 * @param shellSkuId   壳 {@code li_goods_sku.id}
 * @param regionId     与 {@code regionGroupId} 二选一
 * @param numberedGate 含号码的 SKU（P2 起按审核门禁限制交付）
 */
public record SkuBinding(
        long id,
        String shellSkuId,
        String shellGoodsId,
        SkuKind skuKind,
        long supplierId,
        long cardProductId,
        Long regionId,
        Long regionGroupId,
        String form,
        long planTemplateId,
        long offeringId,
        ListingState listingState,
        boolean numberedGate) {

    public enum SkuKind { NEW_CARD, TOP_UP }

    public enum ListingState { DRAFT, LISTED, DELISTED }
}
