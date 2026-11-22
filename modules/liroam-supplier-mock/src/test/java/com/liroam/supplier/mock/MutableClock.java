package com.liroam.supplier.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 测试用可拨的 UTC 时钟。 */
final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant now) {
        this.now = now;
    }

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
        return now;
    }
}
