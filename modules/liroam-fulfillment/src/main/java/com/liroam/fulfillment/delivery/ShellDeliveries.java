package com.liroam.fulfillment.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.store.ShellDeliveryQueue;
import com.liroam.fulfillment.store.ShellDeliveryQueue.Pending;
import com.liroam.fulfillment.worker.Backoff;

/**
 * H5 发货回写的出口：交码事务里只记待回写（{@link #enqueue}），worker 在事务外调 {@link ShellOrderDelivery}；
 * 失败记错误、按指令同一套退避重试，不吞异常也不丢。没有装配端口实现的进程（如买家端）只积压不调用。
 */
public class ShellDeliveries {

    private static final Logger log = LoggerFactory.getLogger(ShellDeliveries.class);
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final TransactionOperations tx;
    private final ShellDeliveryQueue queue;
    private final Optional<ShellOrderDelivery> port;
    private final Settings settings;
    private final Clock clock;

    public ShellDeliveries(TransactionOperations tx, ShellDeliveryQueue queue, Optional<ShellOrderDelivery> port, Settings settings, Clock clock) {
        this.tx = tx;
        this.queue = queue;
        this.port = port;
        this.settings = settings;
        this.clock = clock;
    }

    /** 在交码事务内调用：整单完成即记一条待回写（同单重复无害）。 */
    public void enqueue(String liOrderSn) {
        queue.enqueue(liOrderSn, clock.instant());
    }

    /** @return 本轮回写成功的订单数 */
    public int runOnce(int batch) {
        if (port.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        List<Pending> due = tx.execute(status -> queue.claim(batch, now, now.plus(LEASE)));
        int sent = 0;
        for (Pending p : due) {
            try {
                port.get().markDelivered(p.liOrderSn());
                tx.executeWithoutResult(status -> queue.done(p.id(), clock.instant()));
                sent++;
            } catch (RuntimeException e) {
                Duration wait = Backoff.delay(p.attempts() + 1, settings.get(FulfillmentSettings.COMMAND_BACKOFF_BASE_S),
                        settings.get(FulfillmentSettings.COMMAND_BACKOFF_MAX_S));
                log.warn("壳订单[{}]发货回写失败，{} 后重试", p.liOrderSn(), wait, e);
                tx.executeWithoutResult(status -> queue.failed(p.id(), e.toString(), clock.instant().plus(wait), clock.instant()));
            }
        }
        return sent;
    }
}
