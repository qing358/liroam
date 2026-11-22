package com.liroam.fulfillment.state;

import com.liroam.common.state.StateMachine;

/**
 * 卡的实名状态（{@code lr_card_asset.kyc_status}，domain-design §7.3 正交属性）。本站不存证件：买家自报承诺已按要求实名，
 * 运营可按供应商回执核验或驳回；驳回后买家可再次自报。
 */
public enum KycStatus {
    NOT_REQUIRED, PENDING, USER_DECLARED, VERIFIED, REJECTED;

    public static final StateMachine<KycStatus> MACHINE = StateMachine.of(KycStatus.class)
            .allow(PENDING, USER_DECLARED, VERIFIED, REJECTED)
            .allow(USER_DECLARED, VERIFIED, REJECTED)
            .allow(REJECTED, USER_DECLARED, VERIFIED)
            .build();

    /** BLOCK_DELIVERY 闸放行的状态。 */
    public boolean passesGate() {
        return this == NOT_REQUIRED || this == USER_DECLARED || this == VERIFIED;
    }

    public boolean declarable() {
        return this == PENDING || this == REJECTED;
    }

    public String text() {
        return switch (this) {
            case NOT_REQUIRED -> "无需实名";
            case PENDING -> "待实名";
            case USER_DECLARED -> "已提交实名";
            case VERIFIED -> "实名已核验";
            case REJECTED -> "实名未通过";
        };
    }
}
