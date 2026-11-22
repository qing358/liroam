package com.liroam.fulfillment.courier.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.liroam.common.safety.Simulated;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.fulfillment.courier.CourierTracking;

/** 本机/测试物流轨迹：按寄出后经过的时间给出揽收 → 运输 → 派送 → 签收（合作快递真值未到前演示用）。 */
@Simulated
public class MockCourierTracking implements CourierTracking {

    public static final String KEY = "mock";

    private final Clock clock;

    public MockCourierTracking(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String key() {
        return KEY;
    }

    private record Step(Duration after, String description) {
    }

    private static final List<Step> STEPS = List.of(
            new Step(Duration.ZERO, "已揽收：快递员已取件"),
            new Step(Duration.ofHours(6), "运输中：已到达转运中心"),
            new Step(Duration.ofHours(24), "派送中：快递员正在派送"),
            new Step(Duration.ofHours(36), "已签收：本人签收"));

    @Override
    public Optional<Track> track(Waybill waybill) {
        Instant now = clock.instant();
        List<TrackEvent> events = STEPS.stream().map(s -> Map.entry(waybill.shippedAt().plus(s.after()), s.description()))
                .filter(e -> !e.getKey().isAfter(now))
                .map(e -> new TrackEvent(DisplayTime.format(e.getKey()), e.getValue()))
                .toList()
                .reversed();
        return Optional.of(new Track(events));
    }
}
