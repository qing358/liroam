package com.liroam.fulfillment.worker;

import java.time.Duration;

/** 指数退避：第 n 次失败后等 base × 2^(n-1) 秒，封顶 max 秒（domain-design §6.3）。 */
public final class Backoff {

    private Backoff() {
    }

    public static Duration delay(int failures, int baseSeconds, int maxSeconds) {
        int shift = Math.min(Math.max(failures - 1, 0), 30);
        long seconds = Math.min((long) baseSeconds << shift, maxSeconds);
        return Duration.ofSeconds(Math.max(seconds, 1));
    }
}
