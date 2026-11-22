package com.liroam.shell.order;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.liroam.catalog.CardForm;
import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.delivery.CardShipments;
import com.liroam.fulfillment.delivery.MultiWaybillMode;
import com.liroam.fulfillment.delivery.CardShipments.OrderCard;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.shell.security.SystemOperator;

import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.dto.OrderFulfillItemDTO;
import cn.lili.modules.order.order.entity.dto.OrderFulfillParamsDTO;
import cn.lili.modules.order.order.entity.enums.FulfillmentModeEnum;
import cn.lili.modules.order.order.entity.enums.OrderStatusEnum;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderPackageService;
import cn.lili.modules.order.order.service.OrderService;

/**
 * H5：整单旅行卡交码完成后把壳订单置为已发货（{@code fulfillOrder}）。
 * <p>
 * 壳的 {@code NO_DELIVERY} / {@code WHOLE} 一次发完订单内全部剩余行，{@code SPLIT} 要逐包带物流：
 * 全是 eSIM/软卡 → {@code NO_DELIVERY}；含实体卡且都在同一运单 → {@code WHOLE} 带该运单；
 * 实体卡分多个运单 → 按 {@link FulfillmentSettings#SHIPPING_MULTI_WAYBILL_MODE}：MANUAL 转人工、SPLIT 逐运单分包；
 * 缺物流记录或混有非旅行卡商品 → 转人工待办，由运营在壳后台发货。
 */
public class ShellShipping implements ShellOrderDelivery {

    private static final Logger log = LoggerFactory.getLogger(ShellShipping.class);

    private final OrderService orders;
    private final OrderItemService orderItems;
    private final OrderPackageService packages;
    private final SkuBindings bindings;
    private final CardShipments shipments;
    private final ManualTasks tasks;
    private final Settings settings;

    public ShellShipping(OrderService orders, OrderItemService orderItems, OrderPackageService packages, SkuBindings bindings, CardShipments shipments,
            ManualTasks tasks, Settings settings) {
        this.orders = orders;
        this.orderItems = orderItems;
        this.packages = packages;
        this.bindings = bindings;
        this.shipments = shipments;
        this.tasks = tasks;
        this.settings = settings;
    }

    @Override
    public void markDelivered(String liOrderSn) {
        Order order = orders.getBySn(liOrderSn);
        if (order == null) {
            throw new IllegalStateException("壳订单不存在：" + liOrderSn);
        }
        MultiWaybillMode multiWaybill = settings.get(FulfillmentSettings.SHIPPING_MULTI_WAYBILL_MODE);
        boolean partlyShipped = OrderStatusEnum.PARTS_DELIVERED.name().equals(order.getOrderStatus());
        if (!OrderStatusEnum.UNDELIVERED.name().equals(order.getOrderStatus()) && !(partlyShipped && multiWaybill == MultiWaybillMode.SPLIT)) {
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
            if (partlyShipped || sims.stream().map(ShellShipping::waybill).distinct().count() > 1) {
                if (multiWaybill == MultiWaybillMode.SPLIT) {
                    splitByWaybill(liOrderSn, items, bound, sims);
                } else {
                    manual(liOrderSn, "实体卡分多个运单寄出，请在订单管理分包发货");
                }
                return;
            }
            params.setFulfillmentMode(FulfillmentModeEnum.WHOLE.name());
            params.setLogisticsId(sims.getFirst().logisticsRef());
            params.setLogisticsNo(sims.getFirst().logisticsNo());
            params.setDeliveryRemark("实体卡已寄出；eSIM 可在「我的卡」查看激活码");
        }
        SystemOperator.call(() -> orders.fulfillOrder(params));
    }

    private static String waybill(OrderCard c) {
        return c.logisticsRef() + "|" + c.logisticsNo();
    }

    /**
     * 每个运单一个 SPLIT 包，包内为该运单上的实体卡按订单行计数；eSIM / 软卡行（无物流）随本次发出的第一个包。
     * 壳里已有同运单的包裹即跳过，所以回写失败重放不会重复发货；壳在部分发货后只接受 SPLIT。
     */
    private void splitByWaybill(String liOrderSn, List<OrderItem> items, Map<String, SkuBinding> bound, List<OrderCard> sims) {
        Set<String> shipped = packages.orderPackageList(liOrderSn).stream().map(p -> p.getLogisticsNo()).collect(Collectors.toSet());
        Map<String, OrderItem> bySn = items.stream().collect(Collectors.toMap(OrderItem::getSn, i -> i));
        Map<String, Integer> remain = new LinkedHashMap<>();
        items.forEach(i -> remain.put(i.getId(), i.getNum() - (i.getDeliverNumber() == null ? 0 : i.getDeliverNumber())));
        Map<String, List<OrderCard>> byWaybill = sims.stream().collect(Collectors.groupingBy(ShellShipping::waybill, LinkedHashMap::new, Collectors.toList()));
        boolean first = true;
        for (List<OrderCard> parcel : byWaybill.values()) {
            if (shipped.contains(parcel.getFirst().logisticsNo())) {
                continue;
            }
            Map<String, Integer> lines = new LinkedHashMap<>();
            parcel.forEach(c -> take(lines, remain, bySn.get(c.sourceLineRef()).getId(), 1));
            if (first) {
                items.stream().filter(i -> !CardForm.shipsPhysically(bound.get(i.getSkuId()).form()))
                        .forEach(i -> take(lines, remain, i.getId(), remain.get(i.getId())));
                first = false;
            }
            if (lines.isEmpty()) {
                continue;
            }
            OrderFulfillParamsDTO params = new OrderFulfillParamsDTO();
            params.setOrderSn(liOrderSn);
            params.setFulfillmentMode(FulfillmentModeEnum.SPLIT.name());
            params.setLogisticsId(parcel.getFirst().logisticsRef());
            params.setLogisticsNo(parcel.getFirst().logisticsNo());
            params.setItems(lines.entrySet().stream().map(e -> {
                OrderFulfillItemDTO line = new OrderFulfillItemDTO();
                line.setOrderItemId(e.getKey());
                line.setDeliveryNum(e.getValue());
                return line;
            }).toList());
            SystemOperator.call(() -> orders.fulfillOrder(params));
        }
    }

    private static void take(Map<String, Integer> lines, Map<String, Integer> remain, String itemId, int wanted) {
        int n = Math.min(wanted, remain.get(itemId));
        if (n > 0) {
            lines.merge(itemId, n, Integer::sum);
            remain.merge(itemId, -n, Integer::sum);
        }
    }

    private void manual(String liOrderSn, String note) {
        log.info("订单[{}]不能自动发货，转人工：{}", liOrderSn, note);
        tasks.open(ManualTasks.Kind.SHELL_SHIPPING, "LI_ORDER", liOrderSn, note, Instant.now());
    }
}
