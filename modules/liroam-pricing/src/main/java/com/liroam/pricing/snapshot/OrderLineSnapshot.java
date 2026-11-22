package com.liroam.pricing.snapshot;

import com.liroam.pricing.snapshot.SnapshotRequest.SourceType;

/**
 * {@code lr_order_line_snapshot} 一行：毛利、佣金、对账都按它算，调价后不变。
 *
 * @param costCnyFen               行合计成本（与 {@code paidFen} 同口径），下单时算不出成本为空
 * @param listPriceFen             下单时 ACTIVE 售价版本的价，没有售价版本为空
 * @param paidDiffersFromListPrice 实付单价 ≠ 售价版本价（壳内促销等），进报表，不拦单
 */
public record OrderLineSnapshot(long id, SourceType sourceType, String sourceLineRef, String liOrderSn, long skuBindingId, long supplierId,
        long cardProductId, Long regionId, Long regionGroupId, String usageRegionText, Long tariffSheetId, Long fxRateVersionId,
        Long costVersionId, Long priceVersionId, long unitPriceFen, long paidFen, Long costCnyFen, int qty, Long topupCardAssetId,
        Long listPriceFen) {

    public boolean paidDiffersFromListPrice() {
        return listPriceFen != null && listPriceFen != unitPriceFen;
    }
}
