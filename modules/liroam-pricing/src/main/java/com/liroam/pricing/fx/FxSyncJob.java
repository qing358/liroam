package com.liroam.pricing.fx;

import java.time.Duration;
import java.time.ZoneId;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import com.liroam.common.setting.Settings;
import com.liroam.pricing.PricingSettings;

/**
 * worker 进程里的汇率定时：按 {@code fx.cron} 同步（每次排下一次时重读配置，后台改了不用重启），
 * 每小时查一次是否超过 {@code fx.stale_hours} 未更新并告警。
 */
public class FxSyncJob implements SchedulingConfigurer {

    private static final Log log = LogFactory.getLog(FxSyncJob.class);
    static final String ACTOR = "system:fx";

    private final FxSync sync;
    private final Settings settings;
    private final ZoneId zone;

    public FxSyncJob(FxSync sync, Settings settings, ZoneId zone) {
        this.sync = sync;
        this.settings = settings;
        this.zone = zone;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(() -> sync.run(ACTOR),
                context -> new CronTrigger(settings.get(PricingSettings.FX_CRON), zone).nextExecution(context));
        registrar.addFixedDelayTask(this::checkStale, Duration.ofHours(1));
    }

    void checkStale() {
        if (sync.isStale()) {
            log.warn("汇率已超过 " + settings.get(PricingSettings.FX_STALE_HOURS) + " 小时未更新，成本按旧汇率计算");
        }
    }
}
