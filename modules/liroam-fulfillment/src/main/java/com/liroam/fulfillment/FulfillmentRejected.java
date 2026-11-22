package com.liroam.fulfillment;

/**
 * 履约入口一次性校验不通过；不落任何台账。
 * 面向用户的提示只取 {@link Reason#userMessage()}：加包页（{@code TopUpIntents.open}）、下单校验（H2）与 {@code accept} 同一份文案。
 */
public class FulfillmentRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_QTY("购买数量不正确"),
        SKU_NOT_BOUND("该商品不是旅行卡商品"),
        SKU_NOT_LISTED("该商品已下架"),
        SKU_KIND_MISMATCH("该商品不能用于当前操作"),
        SUPPLIER_NOT_LISTABLE("该商品暂不可售"),
        NUMBERED_SKU_DISABLED("该商品暂不可售"),
        TOPUP_CARD_REQUIRED("请先选择要加包的卡"),
        TOPUP_QTY_NOT_ONE("加包每次只能购买 1 份，请分多次下单"),
        TOPUP_CARD_NOT_FOUND("找不到这张卡"),
        TOPUP_CARD_NOT_OWNED("只能给自己的卡加包"),
        TOPUP_CARD_OTHER_SUPPLIER("这个加包与该卡不匹配，请选择同一卡型的加包"),
        TOPUP_CARD_REFUND_HOLD("该卡退款处理中，暂不能加包"),
        TOPUP_CARD_CLOSED("该卡已作废或终止，不能加包");

        private final String userMessage;

        Reason(String userMessage) {
            this.userMessage = userMessage;
        }

        public String userMessage() {
            return userMessage;
        }
    }

    private final Reason reason;

    public FulfillmentRejected(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
