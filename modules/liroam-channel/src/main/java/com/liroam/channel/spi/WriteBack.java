package com.liroam.channel.spi;

import java.util.List;

/** 一条渠道订单行的回传内容：行内每张卡的交码（eSIM 激活码）或发货物流（实体卡）。 */
public record WriteBack(List<DeliveredCard> cards) {

    public WriteBack {
        cards = List.copyOf(cards);
    }

    /**
     * @param credentialKind   交码种类（如 LPA、SHIPMENT）
     * @param activationCode   eSIM 激活码明文；实体卡为空。不进日志
     * @param logisticsCompany 实体卡物流公司；eSIM 为空
     */
    public record DeliveredCard(String cardNo, String form, String credentialKind, String activationCode, String logisticsCompany,
            String logisticsNo) {

        @Override
        public String toString() {
            return "DeliveredCard[" + cardNo + ", " + form + ", " + credentialKind + ", code=***, " + logisticsCompany + " " + logisticsNo + "]";
        }
    }
}
