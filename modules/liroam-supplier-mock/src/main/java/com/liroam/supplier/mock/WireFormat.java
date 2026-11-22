package com.liroam.supplier.mock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

import com.liroam.supplier.spi.UsageSnapshot.CardStatus;

/** 模拟上游在线路上的计量单位、时间串与状态码；适配器据此归一到字节、UTC 与领域状态。 */
enum WireFormat {

    /** 形状 A：流量 MB，时间为东八区本地时间 {@code yyyy-MM-dd HH:mm:ss}，状态为数字码。 */
    MEGABYTES_LOCAL_TIME(1024L * 1024L, false, Map.of(
            CardStatus.INACTIVE, "2", CardStatus.ACTIVE, "4", CardStatus.SUSPENDED, "5",
            CardStatus.TERMINATED, "6", CardStatus.EXPIRED, "7")),

    /** 形状 B：字节，ISO-8601 UTC，状态为英文码；另报语音秒与短信条。 */
    BYTES_ISO(1L, true, Map.of(
            CardStatus.INACTIVE, "PENDING_ACTIVATION", CardStatus.ACTIVE, "ACTIVE", CardStatus.SUSPENDED, "SUSPENDED",
            CardStatus.TERMINATED, "TERMINATED", CardStatus.EXPIRED, "EXPIRED"));

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneOffset LOCAL_OFFSET = ZoneOffset.ofHours(8);

    private final long bytesPerUnit;
    private final boolean metersVoiceAndSms;
    private final Map<CardStatus, String> statusCodes;
    private final Map<String, CardStatus> statusByCode;

    WireFormat(long bytesPerUnit, boolean metersVoiceAndSms, Map<CardStatus, String> statusCodes) {
        this.bytesPerUnit = bytesPerUnit;
        this.metersVoiceAndSms = metersVoiceAndSms;
        this.statusCodes = statusCodes;
        this.statusByCode = statusCodes.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
    }

    long toUnits(long bytes) {
        return bytes / bytesPerUnit;
    }

    long toBytes(long units) {
        return units * bytesPerUnit;
    }

    boolean metersVoiceAndSms() {
        return metersVoiceAndSms;
    }

    String formatTime(Instant instant) {
        return this == MEGABYTES_LOCAL_TIME ? LOCAL_TIME.format(LocalDateTime.ofInstant(instant, LOCAL_OFFSET)) : instant.toString();
    }

    Instant parseTime(String raw) {
        return this == MEGABYTES_LOCAL_TIME ? LocalDateTime.parse(raw, LOCAL_TIME).toInstant(LOCAL_OFFSET) : Instant.parse(raw);
    }

    String statusCode(CardStatus status) {
        return statusCodes.get(status);
    }

    /** 未登记的码返回 null。 */
    CardStatus status(String code) {
        return statusByCode.get(code);
    }
}
