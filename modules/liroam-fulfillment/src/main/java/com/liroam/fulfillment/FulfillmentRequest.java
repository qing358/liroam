package com.liroam.fulfillment;

import java.util.Objects;

/**
 * 一条待履约的订单行（domain-design §7.1）。幂等键 = ({@code sourceType}, {@code sourceLineRef})。
 *
 * @param sourceRef         订单号 / 渠道单号
 * @param sourceLineRef     自营 = {@code li_order_item.sn}；渠道 = {@code {channel}:{外部行号}}
 * @param ownerMemberId     买家（壳 {@code li_member.id}）；渠道单可空
 * @param topupCardAssetId  加包挂到哪张卡，{@link SourceType#TOPUP} 必填
 */
public record FulfillmentRequest(
        SourceType sourceType,
        String sourceRef,
        String sourceLineRef,
        long skuBindingId,
        int qty,
        String ownerMemberId,
        Long topupCardAssetId,
        Long channelOrderLineId) {

    public enum SourceType { SHOP, CHANNEL, TOPUP }

    public FulfillmentRequest {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(sourceLineRef, "sourceLineRef");
    }
}
