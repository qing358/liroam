package com.liroam.fulfillment.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.liroam.fulfillment.poll.PollScheduler;

/**
 * 履约后台节拍：拆卡、指令执行、轮询排队。只在 {@code liroam.worker.enabled=true} 的进程注册（与壳补偿扫描同一开关）；
 * 宿主进程须已开启 {@code @EnableScheduling}。多实例同时开也安全：认领靠租约与 SKIP LOCKED。
 */
public class FulfillmentSchedules {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentSchedules.class);

    private final FulfillmentWorker worker;
    private final CommandExecutor executor;
    private final PollScheduler polls;
    private final int batch;
    private final String workerId;

    public FulfillmentSchedules(FulfillmentWorker worker, CommandExecutor executor, PollScheduler polls, int batch, String workerId) {
        this.worker = worker;
        this.executor = executor;
        this.polls = polls;
        this.batch = batch;
        this.workerId = workerId;
    }

    @Scheduled(initialDelayString = "${liroam.worker.fulfillment.initial-delay:PT10S}", fixedDelayString = "${liroam.worker.fulfillment.interval:PT5S}")
    public void expandLines() {
        guard("拆卡", () -> worker.runOnce(batch));
    }

    @Scheduled(initialDelayString = "${liroam.worker.executor.initial-delay:PT10S}", fixedDelayString = "${liroam.worker.executor.interval:PT5S}")
    public void executeCommands() {
        guard("指令执行", () -> executor.runOnce(batch, workerId));
    }

    @Scheduled(initialDelayString = "${liroam.worker.poll.initial-delay:PT30S}", fixedDelayString = "${liroam.worker.poll.interval:PT30S}")
    public void schedulePolls() {
        guard("轮询排队", polls::runOnce);
    }

    private static void guard(String what, Runnable tick) {
        try {
            tick.run();
        } catch (RuntimeException e) {
            log.error("履约节拍「{}」本轮失败，下轮重试", what, e);
        }
    }
}
