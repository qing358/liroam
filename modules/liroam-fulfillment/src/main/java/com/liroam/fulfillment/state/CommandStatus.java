package com.liroam.fulfillment.state;

import java.util.EnumSet;

import com.liroam.common.state.StateMachine;

/** 供应商指令状态（domain-design §6.3）。 */
public enum CommandStatus {
    PENDING, RUNNING, LOOKUP_PENDING, SUCCEEDED, MANUAL, CANCELLED;

    public static final StateMachine<CommandStatus> MACHINE = StateMachine.of(CommandStatus.class)
            .allow(PENDING, RUNNING)
            .allow(RUNNING, SUCCEEDED, PENDING, MANUAL, LOOKUP_PENDING)
            .allow(LOOKUP_PENDING, SUCCEEDED, PENDING, MANUAL)
            .allow(MANUAL, PENDING, SUCCEEDED)
            .allowFromEach(EnumSet.of(PENDING, RUNNING, LOOKUP_PENDING, MANUAL), CANCELLED)
            .build();
}
