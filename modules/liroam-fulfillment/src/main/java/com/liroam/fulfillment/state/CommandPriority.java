package com.liroam.fulfillment.state;

/** 指令优先级（{@code lr_supplier_command.priority}，小者先认领）。 */
public enum CommandPriority {
    WRITE(0), ON_DEMAND(1), POLL(2);

    private final int value;

    CommandPriority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
