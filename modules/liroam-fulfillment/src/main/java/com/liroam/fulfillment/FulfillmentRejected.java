package com.liroam.fulfillment;

/** 履约入口一次性校验不通过；不落任何台账。 */
public class FulfillmentRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_QTY,
        SKU_NOT_BOUND,
        SKU_NOT_LISTED,
        SKU_KIND_MISMATCH,
        SUPPLIER_NOT_LISTABLE,
        TOPUP_CARD_REQUIRED,
        TOPUP_QTY_NOT_ONE,
        TOPUP_CARD_NOT_FOUND,
        TOPUP_CARD_NOT_OWNED,
        TOPUP_CARD_OTHER_SUPPLIER,
        TOPUP_CARD_REFUND_HOLD,
        TOPUP_CARD_CLOSED
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
