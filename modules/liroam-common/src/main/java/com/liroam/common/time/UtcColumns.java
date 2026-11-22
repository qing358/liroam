package com.liroam.common.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 自有表 {@code DATETIME(3)} 一律存 UTC。壳的 JDBC URL 带 {@code serverTimezone=Asia/Shanghai}，
 * 直接绑定 {@code Instant}/{@code Timestamp} 会被驱动按会话时区换算；这里只绑定 UTC 的 {@code LocalDateTime}，驱动原样写入。
 */
public final class UtcColumns {

    private UtcColumns() {
    }

    public static LocalDateTime toColumn(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromColumn(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
