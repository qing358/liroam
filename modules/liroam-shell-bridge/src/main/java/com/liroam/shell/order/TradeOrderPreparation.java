package com.liroam.shell.order;

import java.util.List;
import java.util.Map;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBinding.SkuKind;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.money.Fen;
import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.FulfillmentRules;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.pricing.snapshot.OrderLineSnapshots;
import com.liroam.pricing.snapshot.SnapshotRequest;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.enums.OrderTypeEnum;
import cn.lili.modules.order.order.service.OrderItemService;

/**
 * H2：一张刚落库的壳订单里，旅行卡行在下单时就过一遍履约规则、绑定加包选卡意图、写订单行快照。
 * <p>
 * 旅行卡只走普通订单（{@code NORMAL}：付款后进待发货，由 H5 交码收尾）；壳的核销型虚拟订单（{@code VIRTUAL}）等其它类型付款后不进待发货，直接拒单。
 * <p>
 * 只读落库后的 {@code li_order_item}（同一事务内可见）：事件里的 {@code OrderVO.orderItems} 是另一批对象，行号与落库行不同。
 */
public class TradeOrderPreparation {

    static final String ORDER_TYPE_MESSAGE = "旅行卡请在商品页点立即购买或加入购物车后下单";

    private final OrderItemService orderItems;
    private final SkuBindings bindings;
    private final FulfillmentRules rules;
    private final TopUpIntents topUpIntents;
    private final OrderLineSnapshots snapshots;

    public TradeOrderPreparation(OrderItemService orderItems, SkuBindings bindings, FulfillmentRules rules, TopUpIntents topUpIntents,
            OrderLineSnapshots snapshots) {
        this.orderItems = orderItems;
        this.bindings = bindings;
        this.rules = rules;
        this.topUpIntents = topUpIntents;
        this.snapshots = snapshots;
    }

    public void prepare(String orderSn, String memberId, String orderType) {
        List<OrderItem> items = orderItems.getByOrderSn(orderSn);
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<String, SkuBinding> bound = bindings.findByShellSkuIds(items.stream().map(OrderItem::getSkuId).distinct().toList());
        if (!bound.isEmpty() && !OrderTypeEnum.NORMAL.name().equals(orderType)) {
            throw new ServiceException(ResultCode.ERROR, ORDER_TYPE_MESSAGE);
        }
        for (OrderItem item : items) {
            SkuBinding sku = bound.get(item.getSkuId());
            if (sku == null) {
                continue;
            }
            boolean topUp = sku.skuKind() == SkuKind.TOP_UP;
            Long card = topUp ? topUpIntents.bind(orderSn, memberId, item.getSkuId()).orElse(null) : null;
            int qty = item.getNum() == null ? 0 : item.getNum();
            rules.verify(new FulfillmentRequest(topUp ? SourceType.TOPUP : SourceType.SHOP, orderSn, item.getSn(), sku.id(), qty, memberId, card, null));
            snapshots.record(new SnapshotRequest(topUp ? SnapshotRequest.SourceType.TOPUP : SnapshotRequest.SourceType.SHOP, item.getSn(), orderSn,
                    sku.id(), Fen.fromYuan(item.getUnitPrice()), Fen.fromYuan(item.getFlowPrice()), qty, card));
        }
    }
}
