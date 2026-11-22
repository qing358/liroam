package com.liroam.shell.order;

import java.util.List;
import java.util.Map;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBinding.SkuKind;
import com.liroam.catalog.SkuBindings;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.TopUpIntents;

import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.service.OrderItemService;

/**
 * 把一张已支付的壳订单翻译成履约请求：只处理绑定了 {@code lr_sku_binding} 的订单行，其余商品不归本站履约。
 * 支付监听（H1）与补偿扫描（H1′）共用这一处。
 */
public class PaidOrderFulfillment {

    private final OrderItemService orderItems;
    private final SkuBindings bindings;
    private final TopUpIntents topUpIntents;
    private final FulfillmentEntry entry;

    public PaidOrderFulfillment(OrderItemService orderItems, SkuBindings bindings, TopUpIntents topUpIntents, FulfillmentEntry entry) {
        this.orderItems = orderItems;
        this.bindings = bindings;
        this.topUpIntents = topUpIntents;
        this.entry = entry;
    }

    /** @return 交给履约入口的订单行数 */
    public int fulfill(String orderSn, String memberId) {
        List<OrderItem> items = orderItems.getByOrderSn(orderSn);
        if (items == null || items.isEmpty()) {
            return 0;
        }
        Map<String, SkuBinding> bound = bindings.findByShellSkuIds(items.stream().map(OrderItem::getSkuId).distinct().toList());
        int accepted = 0;
        for (OrderItem item : items) {
            SkuBinding sku = bound.get(item.getSkuId());
            if (sku == null) {
                continue;
            }
            boolean topUp = sku.skuKind() == SkuKind.TOP_UP;
            Long card = topUp ? topUpIntents.boundCard(orderSn, item.getSkuId()).orElse(null) : null;
            int qty = item.getNum() == null ? 0 : item.getNum();
            entry.accept(new FulfillmentRequest(topUp ? SourceType.TOPUP : SourceType.SHOP, orderSn, item.getSn(), sku.id(), qty, memberId, card, null));
            accepted++;
        }
        return accepted;
    }
}
