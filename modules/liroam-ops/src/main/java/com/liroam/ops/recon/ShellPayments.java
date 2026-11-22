package com.liroam.ops.recon;

import java.time.LocalDate;
import java.util.List;

/** 壳已支付订单（H1 只读）端口：实现在 shell-bridge，按壳时区的自然日取 {@code li_order} 已支付订单。 */
public interface ShellPayments {

    /** @param paymentMethod 壳支付方式（WECHAT / ALIPAY / WALLET …） */
    record PaidOrder(String orderSn, long amountFen, String paymentMethod, String tradeNo) {
    }

    List<PaidOrder> paidOrders(LocalDate day);
}
