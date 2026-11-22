package com.liroam.fulfillment.state;

import java.util.EnumSet;

import com.liroam.common.state.StateMachine;

/** 履约行状态（domain-design §7.2）。PARTIAL 的异常卡人工处理后全部交码 → COMPLETED；部分退款后余下的卡也退完 → CANCELLED。 */
public enum FulfillmentLineStatus {
    RECEIVED, EXPANDED, IN_PROGRESS, COMPLETED, PARTIAL, CANCELLED, PARTIAL_REFUNDED;

    public static final StateMachine<FulfillmentLineStatus> MACHINE = StateMachine.of(FulfillmentLineStatus.class)
            .allow(RECEIVED, EXPANDED)
            .allow(EXPANDED, IN_PROGRESS)
            .allow(IN_PROGRESS, COMPLETED)
            .allow(PARTIAL, COMPLETED)
            .allowFromEach(EnumSet.of(EXPANDED, IN_PROGRESS), PARTIAL)
            .allowFromEach(EnumSet.of(RECEIVED, EXPANDED, IN_PROGRESS, COMPLETED, PARTIAL, PARTIAL_REFUNDED), CANCELLED)
            .allowFromEach(EnumSet.of(EXPANDED, IN_PROGRESS, COMPLETED, PARTIAL), PARTIAL_REFUNDED)
            .build();
}
