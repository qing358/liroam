package com.liroam.fulfillment.state;

import com.liroam.common.state.StateMachine;

/**
 * 卡资产状态（domain-design §7.3）。{@code EXCEPTION} 记 {@code exception_from}，
 * 只能回到进入前的状态或作废/终止，回退目标由调用方按 {@code exception_from} 校验（{@link #canLeaveException}）。
 */
public enum CardAssetStatus {
    CREATED, ALLOCATED, PROVISIONED, DELIVERED, IN_USE, SUSPENDED, EXPIRED, CANCELLED, TERMINATED, EXCEPTION;

    public static final StateMachine<CardAssetStatus> MACHINE = StateMachine.of(CardAssetStatus.class)
            .allow(CREATED, ALLOCATED, CANCELLED, EXCEPTION)
            .allow(ALLOCATED, PROVISIONED, CANCELLED, EXCEPTION)
            .allow(PROVISIONED, DELIVERED, CANCELLED, TERMINATED, EXCEPTION)
            .allow(DELIVERED, IN_USE, SUSPENDED, TERMINATED, EXPIRED)
            .allow(IN_USE, SUSPENDED, EXPIRED, TERMINATED)
            .allow(SUSPENDED, IN_USE, DELIVERED, TERMINATED)
            .allow(EXPIRED, IN_USE, TERMINATED)
            .allow(EXCEPTION, CREATED, ALLOCATED, PROVISIONED, CANCELLED, TERMINATED)
            .build();

    /** 离开 EXCEPTION：只能回到 {@code exceptionFrom} 或作废/终止。 */
    public static boolean canLeaveException(CardAssetStatus exceptionFrom, CardAssetStatus to) {
        return to == CANCELLED || to == TERMINATED || to == exceptionFrom;
    }
}
