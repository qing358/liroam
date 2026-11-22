package com.liroam.shell.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBinding.ListingState;
import com.liroam.catalog.SkuBinding.SkuKind;
import com.liroam.catalog.SkuBindings;
import com.liroam.fulfillment.delivery.CardShipments;
import com.liroam.fulfillment.delivery.CardShipments.OrderCard;
import com.liroam.fulfillment.task.ManualTasks;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.dto.OrderFulfillParamsDTO;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderService;

/** H5：整单旅行卡交码完成后以系统管理员身份调壳 {@code fulfillOrder}：全无物流 → NO_DELIVERY；实体卡同一运单 → WHOLE；其余转人工。 */
class ShellShippingTest {

    private final OrderService orders = mock(OrderService.class);
    private final OrderItemService items = mock(OrderItemService.class);
    private final SkuBindings bindings = mock(SkuBindings.class);
    private final ManualTasks tasks = mock(ManualTasks.class);
    private final CardShipments shipments = mock(CardShipments.class);
    private final ShellShipping shipping = new ShellShipping(orders, items, bindings, shipments, tasks);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Order order(String status) {
        Order o = new Order();
        o.setSn("O-1");
        o.setOrderStatus(status);
        return o;
    }

    private static OrderItem item(String sku) {
        OrderItem i = new OrderItem();
        i.setSkuId(sku);
        i.setNum(1);
        return i;
    }

    private static SkuBinding binding(String sku, String form) {
        return new SkuBinding(1, sku, "G", SkuKind.NEW_CARD, 1, 10, 100L, null, form, 1, 1, ListingState.LISTED, false, SkuBinding.KycGateMode.NONE);
    }

    @Test
    void allEsimOrderShipsAsNoDeliveryUnderSystemManagerAndRestoresContext() {
        when(orders.getBySn("O-1")).thenReturn(order("UNDELIVERED"));
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("S-A"), item("S-B")));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-A", binding("S-A", "ESIM"), "S-B", binding("S-B", "ESIM")));
        AtomicReference<AuthUser> during = new AtomicReference<>();
        when(orders.fulfillOrder(any())).thenAnswer(inv -> {
            during.set(UserContext.getCurrentUser());
            return null;
        });

        shipping.markDelivered("O-1");

        ArgumentCaptor<OrderFulfillParamsDTO> params = ArgumentCaptor.forClass(OrderFulfillParamsDTO.class);
        verify(orders).fulfillOrder(params.capture());
        assertThat(params.getValue().getOrderSn()).isEqualTo("O-1");
        assertThat(params.getValue().getFulfillmentMode()).isEqualTo("NO_DELIVERY");
        assertThat(params.getValue().getLogisticsId()).isNull();
        assertThat(during.get().getRole()).isEqualTo(UserEnums.MANAGER);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void alreadyShippedOrderIsANoOp() {
        when(orders.getBySn("O-1")).thenReturn(order("DELIVERED"));

        shipping.markDelivered("O-1");

        verify(orders, never()).fulfillOrder(any());
    }

    @Test
    void mixedOrderGoesToManualShippingInsteadOfShippingOtherGoods() {
        when(orders.getBySn("O-1")).thenReturn(order("UNDELIVERED"));
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("S-A"), item("S-PLAIN")));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-A", binding("S-A", "ESIM")));

        shipping.markDelivered("O-1");

        verify(orders, never()).fulfillOrder(any());
        verify(tasks).open(eq(ManualTasks.Kind.SHELL_SHIPPING), eq("LI_ORDER"), eq("O-1"), anyString(), any());
    }

    private static OrderCard card(long id, String form, String ref, String no) {
        return new OrderCard(id, "O-1-" + id, form, ref, ref == null ? null : "顺丰速运", no);
    }

    private void physicalOrder(List<OrderCard> cards) {
        when(orders.getBySn("O-1")).thenReturn(order("UNDELIVERED"));
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("S-P"), item("S-A")));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-P", binding("S-P", "PHYSICAL"), "S-A", binding("S-A", "ESIM")));
        when(shipments.forOrder("O-1")).thenReturn(cards);
    }

    @Test
    void physicalCardsOnOneWaybillShipWholeOrderWithThatWaybill() {
        physicalOrder(List.of(card(1, "PHYSICAL", "LOG-SF", "SF1"), card(2, "PHYSICAL", "LOG-SF", "SF1"), card(3, "ESIM", null, null)));

        shipping.markDelivered("O-1");

        ArgumentCaptor<OrderFulfillParamsDTO> params = ArgumentCaptor.forClass(OrderFulfillParamsDTO.class);
        verify(orders).fulfillOrder(params.capture());
        assertThat(params.getValue()).extracting(OrderFulfillParamsDTO::getFulfillmentMode, OrderFulfillParamsDTO::getLogisticsId,
                OrderFulfillParamsDTO::getLogisticsNo).containsExactly("WHOLE", "LOG-SF", "SF1");
        verify(tasks, never()).open(any(), any(), anyString(), any(), any());
    }

    @Test
    void physicalCardsOnSeveralWaybillsGoToManualSplitShipping() {
        physicalOrder(List.of(card(1, "PHYSICAL", "LOG-SF", "SF1"), card(2, "PHYSICAL", "LOG-SF", "SF2")));

        shipping.markDelivered("O-1");

        verify(orders, never()).fulfillOrder(any());
        verify(tasks).open(eq(ManualTasks.Kind.SHELL_SHIPPING), eq("LI_ORDER"), eq("O-1"), eq("实体卡分多个运单寄出，请在订单管理分包发货"), any());
    }

    @Test
    void physicalCardWithoutShipmentRecordGoesToManualShipping() {
        physicalOrder(List.of(card(1, "PHYSICAL", null, null)));

        shipping.markDelivered("O-1");

        verify(orders, never()).fulfillOrder(any());
        verify(tasks).open(eq(ManualTasks.Kind.SHELL_SHIPPING), eq("LI_ORDER"), eq("O-1"), eq("实体卡缺物流记录，请核对后在订单管理发货"), any());
    }

    @Test
    void missingOrderFailsSoTheWorkerRetries() {
        assertThatThrownBy(() -> shipping.markDelivered("O-X")).isInstanceOf(IllegalStateException.class);
    }
}
