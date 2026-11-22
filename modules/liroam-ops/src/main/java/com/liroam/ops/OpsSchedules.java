package com.liroam.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.liroam.ops.notify.ExpiryReminders;

/** 运营节拍：到期提醒扫描。只在 {@code liroam.worker.enabled=true} 的进程注册；多实例同开靠去重键只发一次。 */
public class OpsSchedules {

    private static final Logger log = LoggerFactory.getLogger(OpsSchedules.class);

    private final ExpiryReminders reminders;

    public OpsSchedules(ExpiryReminders reminders) {
        this.reminders = reminders;
    }

    @Scheduled(initialDelayString = "${liroam.worker.expiry-remind.initial-delay:PT1M}", fixedDelayString = "${liroam.worker.expiry-remind.interval:PT15M}")
    public void remindExpiry() {
        try {
            reminders.runOnce();
        } catch (RuntimeException e) {
            log.error("到期提醒本轮失败，下轮重试", e);
        }
    }
}
