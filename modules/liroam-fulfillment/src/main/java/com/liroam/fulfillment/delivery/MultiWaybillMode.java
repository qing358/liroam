package com.liroam.fulfillment.delivery;

/** 一单实体卡分多个运单寄出时，壳订单怎么发货。 */
public enum MultiWaybillMode {
    /** 转人工待办，运营在订单管理分包发货。 */
    MANUAL,
    /** 按运单逐个分包发货（壳 SPLIT），eSIM / 软卡行随第一个包。 */
    SPLIT
}
