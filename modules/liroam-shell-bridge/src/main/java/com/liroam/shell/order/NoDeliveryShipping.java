package com.liroam.shell.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.fulfillment.ShellOrderDelivery;
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
 * H5：整单旅行卡交码完成后把壳订单置为已发货（{@code fulfillOrder(NO_DELIVERY)}）。
 * <p>
 * 壳的 {@code NO_DELIVERY} 一次发完订单内全部剩余行，{@code SPLIT} 又必须带物流：所以只有整单都是无需物流的旅行卡行才自动发货；
 * 混有其它商品或实体卡的订单转人工待办，由运营在壳后台录物流发货。
 */
public class NoDeliveryShipping implements ShellOrderDelivery {

    private static final Logger log = LoggerFactory.getLogger(NoDeliveryShipping.class);
    private static final Set<String> NO_LOGISTICS_FORMS = Set.of("ESIM", "SOFT");

    private final OrderService orders;
    private final OrderItemService orderItems;
    private final SkuBindings bindings;
    private final ManualTasks tasks;

    public NoDeliveryShipping(OrderService orders, OrderItemService orderItems, SkuBindings bindings, ManualTasks tasks) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.bindings = bindings;
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
        boolean allNoLogistics = items.stream().map(i -> bound.get(i.getSkuId()))
                .allMatch(b -> b != null && NO_LOGISTICS_FORMS.contains(b.form()));
        if (!allNoLogistics) {
            log.info("订单[{}]含实体卡或其它商品，旅行卡已交码，转人工发货", liOrderSn);
            tasks.open(ManualTasks.Kind.SHELL_SHIPPING, "LI_ORDER", liOrderSn, "旅行卡已全部交码；订单含实体卡或其它商品，请在订单管理录物流发货", Instant.now());
            return;
        }
        OrderFulfillParamsDTO params = new OrderFulfillParamsDTO();
        params.setOrderSn(liOrderSn);
        params.setFulfillmentMode(FulfillmentModeEnum.NO_DELIVERY.name());
        params.setDeliveryRemark("旅行卡已交码，可在「我的卡」查看激活码");
        SystemOperator.call(() -> orders.fulfillOrder(params));
    }
}
