package com.liroam.fulfillment.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 测试用可拨时钟（UTC）：退避、冷却、超时都按它算。 */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-22T04:00:00Z");

    private volatile Instant now = START;

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void set(Instant instant) {
        now = instant;
    }

    public void reset() {
        now = START;
    }
}
