package com.liroam.common.ops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 汇总各 {@link OpsAlertSource}：严重的在前；某个来源自己查挂了也算一条告警，不吞。 */
public class OpsAlerts {

    private static final Logger log = LoggerFactory.getLogger(OpsAlerts.class);

    private final Supplier<List<OpsAlertSource>> sources;
    private final Supplier<Instant> now;

    public OpsAlerts(Supplier<List<OpsAlertSource>> sources, Supplier<Instant> now) {
        this.sources = sources;
        this.now = now;
    }

    public List<OpsAlert> current() {
        List<OpsAlert> alerts = new ArrayList<>();
        Instant at = now.get();
        for (OpsAlertSource source : sources.get()) {
            try {
                alerts.addAll(source.check(at));
            } catch (RuntimeException e) {
                log.warn("告警来源 {} 检查失败", source.getClass().getSimpleName(), e);
                alerts.add(new OpsAlert("ALERT_SOURCE_FAILED", OpsAlert.Level.CRITICAL, "告警检查失败",
                        source.getClass().getSimpleName() + "：" + e.getMessage(), 1));
            }
        }
        alerts.sort(Comparator.comparing(OpsAlert::level).thenComparing(OpsAlert::code));
        return alerts;
    }

    /** 定时调用：每条告警打一行 WARN，交给日志采集/告警通道。@return 告警条数 */
    public int logCurrent() {
        List<OpsAlert> alerts = current();
        alerts.forEach(a -> log.warn("运维告警[{}][{}] {}：{}（{}）", a.level(), a.code(), a.title(), a.detail(), a.count()));
        return alerts.size();
    }
}
