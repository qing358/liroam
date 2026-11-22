package com.liroam.supplier.spi;

import java.time.Instant;
import java.util.List;

/**
 * 一次用量查询（或回调）的归一结果：数据字节、语音秒、短信条，时间为 UTC；上游原值另存。
 *
 * @param cardUpstreamRef 回调场景用于定位卡；轮询场景与请求的卡一致
 * @param rawJson         上游原始响应（已脱敏），入 {@code raw_json}
 */
public record UsageSnapshot(String cardUpstreamRef, CardStatus status, List<PlanUsage> plans, String rawJson) {

    public UsageSnapshot {
        plans = List.copyOf(plans);
    }

    public enum CardStatus { INACTIVE, ACTIVE, SUSPENDED, TERMINATED, EXPIRED }

    /** 某个维度未声明时对应字段为空。 */
    public record PlanUsage(
            String upstreamPlanRef,
            Long quotaBytes,
            Long usedBytes,
            Long remainingBytes,
            Long voiceRemainingSeconds,
            Integer smsRemaining,
            Instant startsAt,
            Instant expiresAt,
            String startsAtRaw,
            String expiresAtRaw) {
    }
}
