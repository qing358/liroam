package com.liroam.common.ops;

import org.springframework.scheduling.annotation.Scheduled;

/** worker 进程定时把告警打成 WARN 日志；宿主须开启 {@code @EnableScheduling}。 */
public class OpsAlertSchedule {

    private final OpsAlerts alerts;

    public OpsAlertSchedule(OpsAlerts alerts) {
        this.alerts = alerts;
    }

    @Scheduled(initialDelayString = "${liroam.worker.alerts.initial-delay:PT1M}", fixedDelayString = "${liroam.worker.alerts.interval:PT5M}")
    public void log() {
        alerts.logCurrent();
    }
}
