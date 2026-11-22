package com.liroam.fulfillment;

/** @param created false = 重复请求，返回的是已有履约行 */
public record FulfillmentAck(long fulfillmentLineId, boolean created) {
}
