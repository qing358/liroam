package com.liroam.supplier.spi;

import java.time.Duration;
import java.util.Objects;

/**
 * 适配器调用结果四态（domain-design §6.3 错误分级）。
 * <ul>
 *   <li>{@link Ok}：上游确认成功。</li>
 *   <li>{@link Retryable}：请求未生效且可重试（连接未建立、限流、5xx）；限流带 {@code retryAfter}。</li>
 *   <li>{@link Rejected}：参数/状态错，或能力不支持；不重试，转人工。</li>
 *   <li>{@link Unknown}：写请求已发出但结果未知（读超时、未登记的上游码）；先查档再定。</li>
 * </ul>
 */
public sealed interface Outcome<T> permits Outcome.Ok, Outcome.Retryable, Outcome.Rejected, Outcome.Unknown {

    record Ok<T>(T value) implements Outcome<T> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    record Retryable<T>(String reason, Duration retryAfter) implements Outcome<T> {
    }

    record Rejected<T>(String code, String message) implements Outcome<T> {
    }

    record Unknown<T>(String reason) implements Outcome<T> {
    }

    static <T> Outcome<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Outcome<T> retryable(String reason, Duration retryAfter) {
        return new Retryable<>(reason, retryAfter);
    }

    static <T> Outcome<T> rejected(String code, String message) {
        return new Rejected<>(code, message);
    }

    static <T> Outcome<T> unknown(String reason) {
        return new Unknown<>(reason);
    }

    /** 能力声明不支持的操作统一用这个码。 */
    String UNSUPPORTED = "UNSUPPORTED_CAPABILITY";
}
