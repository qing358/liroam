package com.liroam.shell.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.ops.recon.ShellPayments.PaidOrder;
import com.liroam.schema.testing.ItSchema;

/** H1 只读：按壳本地自然日取已支付（含之后退款）的订单，金额元转分。 */
class ShellPaidOrdersTest {

    private static ShellPaidOrders orders;

    @BeforeAll
    static void setUp() {
        DataSource ds = ItSchema.migrated("shell_paid_orders");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS li_order");
        jdbc.execute("CREATE TABLE li_order (id BIGINT PRIMARY KEY, sn VARCHAR(255), flow_price DECIMAL(10,2), payment_method VARCHAR(255), "
                + "receivable_no VARCHAR(255), pay_status VARCHAR(255), payment_time DATETIME(6)) DEFAULT CHARSET = utf8mb3 COLLATE = utf8mb3_general_ci");
        jdbc.update("INSERT INTO li_order VALUES (1, 'O1', 100.00, 'WECHAT', 'T1', 'PAID', '2026-11-22 00:00:00'), "
                + "(2, 'O2', 49.90, 'WALLET', '', 'REFUNDED', '2026-11-22 23:59:59'), (3, 'O3', 10, 'ALIPAY', 'T3', 'PAID', '2026-11-23 00:00:00'), "
                + "(4, 'O4', 10, 'ALIPAY', NULL, 'UNPAID', NULL)");
        orders = new ShellPaidOrders(JdbcClient.create(ds));
    }

    @Test
    void localDayInclusiveStartExclusiveEnd() {
        assertThat(orders.paidOrders(LocalDate.parse("2026-11-22"))).containsExactly(new PaidOrder("O1", 10_000, "WECHAT", "T1"),
                new PaidOrder("O2", 4_990, "WALLET", null));
        assertThat(orders.paidOrders(LocalDate.parse("2026-11-23"))).extracting(PaidOrder::orderSn).containsExactly("O3");
    }
}
