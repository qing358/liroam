package com.liroam.catalog;

/**
 * 壳 SKU → 本站卡品的绑定（{@code lr_sku_binding}）。每个 SKU 只绑一家供应商；首次上架后供应商/卡产品/使用区域锁定。
 *
 * @param shellSkuId   壳 {@code li_goods_sku.id}
 * @param regionId     与 {@code regionGroupId} 二选一
 * @param form         {@link CardForm} 名
 * @param numberedGate 含号码的 SKU：全局可售开关 {@link CatalogSettings#NUMBERED_SKU_ENABLED} 关闭时不能下单
 * @param kycGateMode  实名闸：交码前是否要求实名（domain-design §7.3）
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
        boolean numberedGate,
        KycGateMode kycGateMode) {

    public enum SkuKind { NEW_CARD, TOP_UP }

    public enum ListingState { DRAFT, LISTED, DELISTED }

    /** NONE 不要求；PROMPT 只提示、不挡交付；BLOCK_DELIVERY 实名自报或核验通过前不交付激活码。 */
    public enum KycGateMode { NONE, PROMPT, BLOCK_DELIVERY }
}
