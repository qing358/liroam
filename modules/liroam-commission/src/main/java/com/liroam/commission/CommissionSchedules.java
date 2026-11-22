package com.liroam.commission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 佣金节拍：入账扫描、到期转可提现。只在 {@code liroam.worker.enabled=true} 的进程注册；多实例同开也安全（入账行级唯一键、结算条件更新）。 */
public class CommissionSchedules {

    private static final Logger log = LoggerFactory.getLogger(CommissionSchedules.class);

    private final CommissionAccruals accruals;
    private final CommissionLedger ledger;
    private final int batch;

    public CommissionSchedules(CommissionAccruals accruals, CommissionLedger ledger, int batch) {
        this.accruals = accruals;
        this.ledger = ledger;
        this.batch = batch;
    }

    @Scheduled(initialDelayString = "${liroam.worker.commission.initial-delay:PT30S}", fixedDelayString = "${liroam.worker.commission.interval:PT1M}")
    public void accrue() {
        guard("佣金入账", () -> accruals.runOnce(batch));
    }

    @Scheduled(initialDelayString = "${liroam.worker.commission.initial-delay:PT30S}", fixedDelayString = "${liroam.worker.commission.settle-interval:PT5M}")
    public void settle() {
        guard("佣金结算", ledger::settleDue);
    }

    private static void guard(String what, Runnable tick) {
        try {
            tick.run();
        } catch (RuntimeException e) {
            log.error("佣金节拍「{}」本轮失败，下轮重试", what, e);
        }
    }
}
