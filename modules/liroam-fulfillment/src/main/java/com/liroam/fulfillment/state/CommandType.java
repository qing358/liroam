package com.liroam.fulfillment.state;

import java.util.Optional;

import com.liroam.supplier.spi.LookupQuery.WriteOperation;

/** 指令类型（domain-design §6.3）。ALLOCATE 为本地卡池分配，不调上游。 */
public enum CommandType {
    ALLOCATE, PROVISION, FETCH_CREDENTIAL, ADD_PLAN, SUSPEND, RESUME, TERMINATE, QUERY;

    /** 结果未知时可查档判定的写操作。 */
    public Optional<WriteOperation> lookupOperation() {
        return switch (this) {
            case PROVISION -> Optional.of(WriteOperation.PROVISION);
            case ADD_PLAN -> Optional.of(WriteOperation.ADD_PLAN);
            case SUSPEND -> Optional.of(WriteOperation.SUSPEND);
            case RESUME -> Optional.of(WriteOperation.RESUME);
            case TERMINATE -> Optional.of(WriteOperation.TERMINATE);
            default -> Optional.empty();
        };
    }

    /** 卡在退款冻结（refund_hold）期间不执行的开通类指令；停用/终止与查询不受影响。 */
    public boolean heldByRefund() {
        return this == ALLOCATE || this == PROVISION || this == FETCH_CREDENTIAL || this == ADD_PLAN;
    }

    public boolean isStatusOp() {
        return this == SUSPEND || this == RESUME || this == TERMINATE;
    }
}
