package com.liroam.fulfillment.state;

import com.liroam.common.state.StateMachine;

/** 卡套餐状态（domain-design §7.4）。 */
public enum CardPlanStatus {
    PENDING, PROVISIONING, READY, IN_USE, DEPLETED, EXPIRED, CANCELLED, FAILED;

    public static final StateMachine<CardPlanStatus> MACHINE = StateMachine.of(CardPlanStatus.class)
            .allow(PENDING, PROVISIONING, CANCELLED, FAILED)
            .allow(PROVISIONING, READY, CANCELLED, FAILED)
            .allow(READY, IN_USE)
            .allow(IN_USE, DEPLETED, EXPIRED)
            .build();
}
