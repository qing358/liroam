package com.liroam.pricing.snapshot;

/**
 * 订单行快照请求。
 *
 * @param sourceLineRef    来源行唯一号（自营为壳 {@code li_order_item.sn}），与 {@code sourceType} 一起幂等
 * @param unitPriceFen     买家实付单价（壳内促销等以实付为准）
 * @param paidFen          行实付合计
 * @param topupCardAssetId 加包行指向的卡，其它为空
 */
public record SnapshotRequest(SourceType sourceType, String sourceLineRef, String liOrderSn, long skuBindingId, long unitPriceFen, long paidFen,
        int qty, Long topupCardAssetId) {

    public enum SourceType { SHOP, CHANNEL, TOPUP }
}
