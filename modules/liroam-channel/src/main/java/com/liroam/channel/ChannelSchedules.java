package com.liroam.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 渠道后台节拍：每分钟看各渠道是否到拉单点、回传交码、每小时补跑昨天的日对账。只在 {@code liroam.worker.enabled=true} 的进程注册；
 * 宿主进程须已开启 {@code @EnableScheduling}。
 */
public class ChannelSchedules {

    private static final Logger log = LoggerFactory.getLogger(ChannelSchedules.class);

    private final ChannelOrders orders;
    private final ChannelWriteBacks writeBacks;
    private final ChannelReconciliation reconciliation;
    private final int batch;

    public ChannelSchedules(ChannelOrders orders, ChannelWriteBacks writeBacks, ChannelReconciliation reconciliation, int batch) {
        this.orders = orders;
        this.writeBacks = writeBacks;
        this.reconciliation = reconciliation;
        this.batch = batch;
    }

    @Scheduled(initialDelayString = "${liroam.worker.channel-pull.initial-delay:PT30S}", fixedDelayString = "${liroam.worker.channel-pull.interval:PT1M}")
    public void pull() {
        guard("拉单", orders::pullDue);
    }

    @Scheduled(initialDelayString = "${liroam.worker.channel-writeback.initial-delay:PT30S}",
            fixedDelayString = "${liroam.worker.channel-writeback.interval:PT30S}")
    public void writeBack() {
        guard("回传", () -> writeBacks.runOnce(batch));
    }

    @Scheduled(initialDelayString = "${liroam.worker.channel-recon.initial-delay:PT5M}", fixedDelayString = "${liroam.worker.channel-recon.interval:PT1H}")
    public void reconcile() {
        guard("日对账", reconciliation::runDueDaily);
    }

    private static void guard(String what, Runnable tick) {
        try {
            tick.run();
        } catch (RuntimeException e) {
            log.error("渠道节拍「{}」本轮失败，下轮重试", what, e);
        }
    }
}
