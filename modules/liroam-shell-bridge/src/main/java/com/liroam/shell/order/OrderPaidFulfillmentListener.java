package com.liroam.shell.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import com.liroam.fulfillment.FulfillmentRejected;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.event.OrderPaidEvent;

/**
 * H1：在支付事务内同步收单。履约行与订单 PAID 同事务提交；收单失败则整笔支付回滚。
 * <p>
 * 任何失败一律以 {@link ServiceException} 抛出：余额支付（{@code WalletPlugin}）先独立扣款，
 * 只有捕获到 {@code ServiceException} 时才把余额退回，其它异常会吞掉余额。
 */
public class OrderPaidFulfillmentListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPaidFulfillmentListener.class);

    private final PaidOrderFulfillment fulfillment;

    public OrderPaidFulfillmentListener(PaidOrderFulfillment fulfillment) {
        this.fulfillment = fulfillment;
    }

    @EventListener
    public void onOrderPaid(OrderPaidEvent event) {
        Order order = event.getOrder();
        try {
            fulfillment.fulfill(order.getSn(), order.getMemberId());
        } catch (ServiceException e) {
            throw e;
        } catch (FulfillmentRejected e) {
            log.warn("订单[{}]旅行卡收单被拒：{}", order.getSn(), e.getMessage());
            throw new ServiceException(ResultCode.ERROR, "旅行卡订单无法履约（" + e.reason() + "），支付已撤销");
        } catch (RuntimeException e) {
            log.error("订单[{}]旅行卡收单失败，支付回滚", order.getSn(), e);
            throw new ServiceException(ResultCode.ERROR, "旅行卡订单收单失败，支付已撤销，请稍后重试");
        }
    }
}
