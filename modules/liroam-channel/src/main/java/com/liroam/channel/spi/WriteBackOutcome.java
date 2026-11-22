package com.liroam.channel.spi;

/**
 * 回传结果：{@link Ok} 渠道已受理；{@link Retryable} 未生效可重试（网络、限流、5xx）；{@link Rejected} 渠道拒绝（单已取消、参数错），不重试转人工。
 */
public sealed interface WriteBackOutcome permits WriteBackOutcome.Ok, WriteBackOutcome.Retryable, WriteBackOutcome.Rejected {

    record Ok() implements WriteBackOutcome {
    }

    record Retryable(String reason) implements WriteBackOutcome {
    }

    record Rejected(String code, String message) implements WriteBackOutcome {
    }
}
