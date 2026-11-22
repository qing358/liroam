package com.liroam.shell.order;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

/**
 * H1′：补偿扫描。找近 {@code lookback} 内已支付、含旅行卡 SKU、却缺履约行的订单，重新走唯一入口。
 * 每单一个事务，单单失败只记日志，不影响其它订单；入口幂等，与支付监听并发无害。
 */
public class PaidOrderCompensationScanner {

    private static final Logger log = LoggerFactory.getLogger(PaidOrderCompensationScanner.class);

    /** {@code li_order.payment_time} 按壳 JDBC 会话时区（{@code shellZone}）存本地时间。 */
    private static final String MISSING = """
            SELECT DISTINCT o.sn, o.member_id
              FROM li_order o
              JOIN li_order_item i ON i.order_sn = o.sn
              JOIN lr_sku_binding b ON b.li_goods_sku_id = i.sku_id
              LEFT JOIN lr_fulfillment_line f ON f.source_line_ref = i.sn AND f.source_type IN ('SHOP', 'TOPUP')
             WHERE o.pay_status = 'PAID' AND o.payment_time >= ? AND f.id IS NULL
             LIMIT ?
            """;

    public record Settings(Duration lookback, ZoneId shellZone, int batchSize) {
    }

    record PaidOrder(String sn, String memberId) {
    }

    private final JdbcClient jdbc;
    private final PaidOrderFulfillment fulfillment;
    private final TransactionOperations tx;
    private final Settings settings;
    private final Clock clock;

    public PaidOrderCompensationScanner(JdbcClient jdbc, PaidOrderFulfillment fulfillment, TransactionOperations tx, Settings settings, Clock clock) {
        this.jdbc = jdbc;
        this.fulfillment = fulfillment;
        this.tx = tx;
        this.settings = settings;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${liroam.worker.paid-order-scan.initial-delay:PT30S}",
            fixedDelayString = "${liroam.worker.paid-order-scan.interval:PT1M}")
    public void scheduledScan() {
        scan();
    }

    /** @return 本轮补收成功的订单数 */
    public int scan() {
        LocalDateTime since = LocalDateTime.now(clock.withZone(settings.shellZone())).minus(settings.lookback());
        List<PaidOrder> missing = jdbc.sql(MISSING).params(since, settings.batchSize()).query(PaidOrder.class).list();
        int recovered = 0;
        for (PaidOrder order : missing) {
            try {
                tx.executeWithoutResult(status -> fulfillment.fulfill(order.sn(), order.memberId()));
                recovered++;
                log.warn("补偿扫描补收订单[{}]：支付监听未落履约行", order.sn());
            } catch (RuntimeException e) {
                log.error("补偿扫描收单失败，订单[{}]转人工排查", order.sn(), e);
            }
        }
        return recovered;
    }
}
