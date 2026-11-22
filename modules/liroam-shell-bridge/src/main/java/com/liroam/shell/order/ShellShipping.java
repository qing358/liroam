package com.liroam.shell.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.liroam.catalog.CardForm;
import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.delivery.CardShipments;
import com.liroam.fulfillment.delivery.CardShipments.OrderCard;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.shell.security.SystemOperator;

import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.dto.OrderFulfillParamsDTO;
import cn.lili.modules.order.order.entity.enums.FulfillmentModeEnum;
import cn.lili.modules.order.order.entity.enums.OrderStatusEnum;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderService;

/**
 * H5：整单旅行卡交码完成后把壳订单置为已发货（{@code fulfillOrder}）。
 * <p>
 * 壳的 {@code NO_DELIVERY} / {@code WHOLE} 一次发完订单内全部剩余行，{@code SPLIT} 要逐包带物流：
 * 全是 eSIM/软卡 → {@code NO_DELIVERY}；含实体卡且都在同一运单 → {@code WHOLE} 带该运单；
 * 实体卡分多个运单、缺物流记录或混有非旅行卡商品 → 转人工待办，由运营在壳后台发货。
 */
public class ShellShipping implements ShellOrderDelivery {

    private static final Logger log = LoggerFactory.getLogger(ShellShipping.class);

    private final OrderService orders;
    private final OrderItemService orderItems;
    private final SkuBindings bindings;
    private final CardShipments shipments;
    private final ManualTasks tasks;

    public ShellShipping(OrderService orders, OrderItemService orderItems, SkuBindings bindings, CardShipments shipments, ManualTasks tasks) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.bindings = bindings;
        this.shipments = shipments;
        this.tasks = tasks;
    }

    @Override
    public void markDelivered(String liOrderSn) {
        Order order = orders.getBySn(liOrderSn);
        if (order == null) {
            throw new IllegalStateException("壳订单不存在：" + liOrderSn);
        }
        if (!OrderStatusEnum.UNDELIVERED.name().equals(order.getOrderStatus())) {
            return;
        }
        List<OrderItem> items = orderItems.getByOrderSn(liOrderSn);
        Map<String, SkuBinding> bound = bindings.findByShellSkuIds(items.stream().map(OrderItem::getSkuId).distinct().toList());
        if (!items.stream().allMatch(i -> bound.containsKey(i.getSkuId()))) {
            manual(liOrderSn, "旅行卡已全部交码；订单含其它商品，请在订单管理录物流发货");
            return;
        }
        boolean physical = items.stream().anyMatch(i -> CardForm.shipsPhysically(bound.get(i.getSkuId()).form()));
        OrderFulfillParamsDTO params = new OrderFulfillParamsDTO();
        params.setOrderSn(liOrderSn);
        if (!physical) {
            params.setFulfillmentMode(FulfillmentModeEnum.NO_DELIVERY.name());
            params.setDeliveryRemark("旅行卡已交码，可在「我的卡」查看激活码");
        } else {
            List<OrderCard> sims = shipments.forOrder(liOrderSn).stream().filter(c -> CardForm.shipsPhysically(c.form())).toList();
            if (sims.isEmpty() || sims.stream().anyMatch(c -> c.logisticsRef() == null || c.logisticsNo() == null)) {
                manual(liOrderSn, "实体卡缺物流记录，请核对后在订单管理发货");
                return;
            }
            if (sims.stream().map(c -> c.logisticsRef() + "|" + c.logisticsNo()).distinct().count() > 1) {
                manual(liOrderSn, "实体卡分多个运单寄出，请在订单管理分包发货");
                return;
            }
            params.setFulfillmentMode(FulfillmentModeEnum.WHOLE.name());
            params.setLogisticsId(sims.getFirst().logisticsRef());
            params.setLogisticsNo(sims.getFirst().logisticsNo());
            params.setDeliveryRemark("实体卡已寄出；eSIM 可在「我的卡」查看激活码");
        }
        SystemOperator.call(() -> orders.fulfillOrder(params));
    }

    private void manual(String liOrderSn, String note) {
        log.info("订单[{}]不能自动发货，转人工：{}", liOrderSn, note);
        tasks.open(ManualTasks.Kind.SHELL_SHIPPING, "LI_ORDER", liOrderSn, note, Instant.now());
    }
}
