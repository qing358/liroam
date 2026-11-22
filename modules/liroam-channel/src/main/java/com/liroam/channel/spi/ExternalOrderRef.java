package com.liroam.channel.spi;

/** 回传定位到渠道订单的哪一行。 */
public record ExternalOrderRef(String externalOrderId, String lineNo, String externalSkuId) {
}
