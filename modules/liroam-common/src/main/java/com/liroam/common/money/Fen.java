package com.liroam.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 人民币元（壳 {@code Double}）↔ 分（自有表 {@code BIGINT}）。元先转 {@link BigDecimal} 再四舍五入到分。 */
public final class Fen {

    private Fen() {
    }

    public static long fromYuan(Double yuan) {
        if (yuan == null) {
            return 0L;
        }
        return BigDecimal.valueOf(yuan).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static Double toYuan(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2).doubleValue();
    }
}
