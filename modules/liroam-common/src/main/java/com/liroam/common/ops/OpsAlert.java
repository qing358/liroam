package com.liroam.common.ops;

/**
 * 一条运维告警（§3.6 监控告警）：后台「运维告警」页与定时 WARN 日志同一份。
 *
 * @param code  稳定代码，如 {@code COMMAND_MANUAL}
 * @param count 命中条数（无计数的为 1）
 */
public record OpsAlert(String code, Level level, String title, String detail, long count) {

    public enum Level { CRITICAL, WARN }
}
