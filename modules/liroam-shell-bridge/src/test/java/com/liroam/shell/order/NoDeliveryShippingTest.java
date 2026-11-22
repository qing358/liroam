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
import com.liroam.fulfillment.task.ManualTasks;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.dto.OrderFulfillParamsDTO;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderService;

/** H5：整单旅行卡交码完成后以系统管理员身份调壳 {@code fulfillOrder(NO_DELIVERY)}。 */
class NoDeliveryShippingTest {

    private final OrderService orders = mock(OrderService.class);
    private final OrderItemService items = mock(OrderItemService.class);
    private final SkuBindings bindings = mock(SkuBindings.class);
    private final ManualTasks tasks = mock(ManualTasks.class);
    private final NoDeliveryShipping shipping = new NoDeliveryShipping(orders, items, bindings, tasks);

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
        return new SkuBinding(1, sku, "G", SkuKind.NEW_CARD, 1, 10, 100L, null, form, 1, 1, ListingState.LISTED, false);
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

    @Test
    void physicalCardOrderAlsoNeedsLogisticsFromOperations() {
        when(orders.getBySn("O-1")).thenReturn(order("UNDELIVERED"));
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("S-P")));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-P", binding("S-P", "PHYSICAL")));

        shipping.markDelivered("O-1");

        verify(orders, never()).fulfillOrder(any());
        verify(tasks).open(eq(ManualTasks.Kind.SHELL_SHIPPING), eq("LI_ORDER"), eq("O-1"), anyString(), any());
    }

    @Test
    void missingOrderFailsSoTheWorkerRetries() {
        assertThatThrownBy(() -> shipping.markDelivered("O-X")).isInstanceOf(IllegalStateException.class);
    }
}
