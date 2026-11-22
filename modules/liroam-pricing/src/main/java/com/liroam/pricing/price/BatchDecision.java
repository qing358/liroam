package com.liroam.pricing.price;

import java.util.List;

/** 调价建议批量确认 / 驳回的结果：逐条独立，失败的不影响其它条。 */
public record BatchDecision(List<Long> done, List<Failure> failed) {

    public record Failure(long id, String message) {
    }
}
