package com.liroam.shell.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.ops.recon.ShellPayments;

/**
 * H1 只读：某自然日（壳时区，{@code li_order.payment_time} 存壳本地时间）已支付的壳订单，供支付日对账。
 * 金额 = {@code flow_price}（元）转分；流水号 = {@code receivable_no}。之后退款的订单当天仍算已支付（支付机构日账单同样列出）。
 */
public class ShellPaidOrders implements ShellPayments {

    private record Row(String sn, BigDecimal flowPrice, String paymentMethod, String receivableNo) {
    }

    private final JdbcClient jdbc;

    public ShellPaidOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PaidOrder> paidOrders(LocalDate day) {
        return jdbc.sql("""
                SELECT sn, flow_price, payment_method, receivable_no FROM li_order
                 WHERE pay_status <> 'UNPAID' AND payment_time >= ? AND payment_time < ?
                 ORDER BY payment_time
                """).params(day.atStartOfDay(), day.plusDays(1).atStartOfDay()).query(Row.class).list().stream()
                .map(r -> new PaidOrder(r.sn(), r.flowPrice() == null ? 0 : r.flowPrice().movePointRight(2).longValueExact(), r.paymentMethod(),
                        r.receivableNo() == null || r.receivableNo().isBlank() ? null : r.receivableNo()))
                .toList();
    }
}
