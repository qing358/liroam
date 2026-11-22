package com.liroam.channel;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import org.springframework.scheduling.support.CronExpression;

import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;

/**
 * 渠道拉单节拍（{@code lr_channel.pull_cron}）：间隔（{@code 5m}、{@code 2h}、ISO {@code PT30S}）或 6 段 Spring cron（按北京时间）。
 * 为空用默认 5 分钟（domain-design §13）。
 */
final class PullSchedule {

    static final Duration DEFAULT = Duration.ofMinutes(5);

    private final Duration interval;
    private final CronExpression cron;

    private PullSchedule(Duration interval, CronExpression cron) {
        this.interval = interval;
        this.cron = cron;
    }

    static PullSchedule parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new PullSchedule(DEFAULT, null);
        }
        String text = raw.trim();
        if (text.contains(" ")) {
            if (!CronExpression.isValidExpression(text)) {
                throw invalid(text);
            }
            return new PullSchedule(null, CronExpression.parse(text));
        }
        Duration interval = interval(text);
        if (interval == null || interval.isNegative() || interval.isZero()) {
            throw invalid(text);
        }
        return new PullSchedule(interval, null);
    }

    Instant nextAfter(Instant last) {
        if (interval != null) {
            return last.plus(interval);
        }
        ZonedDateTime next = cron.next(last.atZone(DisplayTime.ZONE));
        return Objects.requireNonNull(next, "cron 没有下一次触发").toInstant();
    }

    private static Duration interval(String text) {
        try {
            if (text.toUpperCase().startsWith("PT")) {
                return Duration.parse(text.toUpperCase());
            }
            if (!text.matches("-?\\d+[smhSMH]")) {
                return null;
            }
            long n = Long.parseLong(text.substring(0, text.length() - 1));
            return switch (Character.toLowerCase(text.charAt(text.length() - 1))) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                default -> Duration.ofHours(n);
            };
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private static UserFacingException invalid(String text) {
        return new UserFacingException("拉单间隔格式不对：" + text + "（如 5m、PT30S 或 6 段 cron）");
    }
}
