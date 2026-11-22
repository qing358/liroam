package com.liroam.channel.spi;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 渠道的一张已付款订单（领域形状）。
 *
 * @param paidFen      买家实付合计（人民币分）
 * @param orderedAtRaw 渠道原样的下单时间文本
 * @param buyerPhone   收货 / 联系手机号明文，入库前加密，不进日志
 * @param rawJson      渠道原始报文，入 {@code raw_json}；实现须先把手机号等个人信息打码
 */
public record ExternalOrder(String externalOrderId, Instant orderedAt, String orderedAtRaw, long paidFen, String buyerPhone,
        List<ExternalOrderLine> lines, String rawJson) {

    public ExternalOrder {
        Objects.requireNonNull(externalOrderId, "externalOrderId");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("渠道订单 " + externalOrderId + " 没有商品行");
        }
    }

    @Override
    public String toString() {
        return "ExternalOrder[" + externalOrderId + ", paidFen=" + paidFen + ", lines=" + lines + "]";
    }
}
