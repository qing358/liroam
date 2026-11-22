package com.liroam.fulfillment.state;

import com.liroam.common.state.StateMachine;

/** 卡套餐状态（domain-design §7.4）。READY → EXPIRED = 未激活即过激活期限（上游判定）。 */
public enum CardPlanStatus {
    PENDING, PROVISIONING, READY, IN_USE, DEPLETED, EXPIRED, CANCELLED, FAILED;

    public static final StateMachine<CardPlanStatus> MACHINE = StateMachine.of(CardPlanStatus.class)
            .allow(PENDING, PROVISIONING, CANCELLED, FAILED)
            .allow(PROVISIONING, READY, CANCELLED, FAILED)
            .allow(READY, IN_USE, EXPIRED)
            .allow(IN_USE, DEPLETED, EXPIRED)
            .build();
}
