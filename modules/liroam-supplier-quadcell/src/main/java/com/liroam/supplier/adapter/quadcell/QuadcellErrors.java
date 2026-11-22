package com.liroam.supplier.adapter.quadcell;

import java.util.HashMap;
import java.util.Map;

/**
 * Open API §7 内部错误码 → 分级（domain-design §6.3）。未登记的码一律 {@link Kind#UNKNOWN}：宁可查档，不盲目重试写。
 * 「Provisioning failed / Update failed」类不确定上游是否已部分生效，按 UNKNOWN 先查档。
 */
final class QuadcellErrors {

    enum Kind { REJECTED, RETRYABLE, UNKNOWN }

    private static final Map<Integer, Kind> TABLE = new HashMap<>();

    static {
        for (int code = 1000; code <= 1008; code++) {
            TABLE.put(code, Kind.REJECTED);
        }
        TABLE.put(1060, Kind.REJECTED);
        TABLE.put(2001, Kind.RETRYABLE);
        for (int code = 2002; code <= 2010; code++) {
            TABLE.put(code, Kind.REJECTED);
        }
        TABLE.put(2031, Kind.REJECTED);
        TABLE.put(2032, Kind.REJECTED);
        for (int code : new int[] {2041, 2042, 3001, 3002, 3003, 3004, 9000}) {
            TABLE.put(code, Kind.UNKNOWN);
        }
    }

    /** 查无此卡（终止后的查档据此判定已生效）。 */
    static final int NO_RECORD = 1060;

    private QuadcellErrors() {
    }

    static Kind classify(int code) {
        return TABLE.getOrDefault(code, Kind.UNKNOWN);
    }
}
