package com.liroam.pricing;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 后台列表的时间文案：库里 UTC，展示按北京时间。 */
public final class DisplayTime {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private DisplayTime() {
    }

    public static String format(Instant instant) {
        return instant == null ? null : FORMAT.format(instant);
    }
}
