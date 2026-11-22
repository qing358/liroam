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
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.delivery.CardShipments;
import com.liroam.fulfillment.delivery.MultiWaybillMode;
import com.liroam.fulfillment.delivery.CardShipments.OrderCard;
import com.liroam.fulfillment.task.ManualTasks;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.dos.OrderPackage;
import cn.lili.modules.order.order.entity.dto.OrderFulfillItemDTO;
import cn.lili.modules.order.order.entity.dto.OrderFulfillParamsDTO;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderPackageService;
import cn.lili.modules.order.order.service.OrderService;

/** H5：整单旅行卡交码完成后以系统管理员身份调壳 {@code fulfillOrder}：全无物流 → NO_DELIVERY；实体卡同一运单 → WHOLE；其余转人工。 */
class ShellShippingTest {

    private final OrderService orders = mock(OrderService.class);
    private final OrderItemService items = mock(OrderItemService.class);
    private final SkuBindings bindings = mock(SkuBindings.class);
    private final ManualTasks tasks = mock(ManualTasks.class);
    private final CardShipments shipments = mock(CardShipments.class);
    private final OrderPackageService packages = mock(OrderPackageService.class);
    private final Settings settings = mock(Settings.class);
    private final ShellShipping shipping = new ShellShipping(orders, items, packages, bindings, shipments, tasks, settings);

    {
        when(settings.get(FulfillmentSettings.SHIPPING_MULTI_WAYBILL_MODE)).thenReturn(MultiWaybillMode.MANUAL);
        when(packages.orderPackageList(anyString())).thenReturn(List.of());
    }

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
    void defaultMultiWaybillModeIsManual() {
        assertThat(FulfillmentSettings.SHIPPING_MULTI_WAYBILL_MODE.defaultValue()).isEqualTo(MultiWaybillMode.MANUAL);
    }

    private static OrderItem item(String id, String sn, String sku, int num) {
        OrderItem i = item(sku);
        i.setId(id);
        i.setSn(sn);
        i.setNum(num);
        i.setDeliverNumber(0);
        return i;
    }

    private static OrderCard card(long id, String lineSn, String form, String ref, String no) {
        return new OrderCard(id, lineSn, form, ref, ref == null ? null : "顺丰速运", no);
    }

    /** 一行 2 张实体卡分两个运单 + 一行实体卡 + 一行 eSIM：按运单各一个 SPLIT 包，eSIM 行随第一个包。 */
    private void splitOrder() {
        when(settings.get(FulfillmentSettings.SHIPPING_MULTI_WAYBILL_MODE)).thenReturn(MultiWaybillMode.SPLIT);
        when(orders.getBySn("O-1")).thenReturn(order("UNDELIVERED"));
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-P", "SN-P", "S-P", 2), item("I-Q", "SN-Q", "S-P", 1), item("I-E", "SN-E", "S-A", 1)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-P", binding("S-P", "PHYSICAL"), "S-A", binding("S-A", "ESIM")));
        when(shipments.forOrder("O-1")).thenReturn(List.of(card(1, "SN-P", "PHYSICAL", "LOG-SF", "SF1"), card(2, "SN-P", "PHYSICAL", "LOG-SF", "SF2"),
                card(3, "SN-Q", "PHYSICAL", "LOG-SF", "SF2"), card(4, "SN-E", "ESIM", null, null)));
    }

    private static Map<String, Integer> lines(OrderFulfillParamsDTO p) {
        return p.getItems().stream().collect(java.util.stream.Collectors.toMap(OrderFulfillItemDTO::getOrderItemId, OrderFulfillItemDTO::getDeliveryNum));
    }

    @Test
    void splitModeShipsOnePackagePerWaybillWithNonPhysicalLinesInTheFirst() {
        splitOrder();

        shipping.markDelivered("O-1");

        ArgumentCaptor<OrderFulfillParamsDTO> params = ArgumentCaptor.forClass(OrderFulfillParamsDTO.class);
        verify(orders, org.mockito.Mockito.times(2)).fulfillOrder(params.capture());
        OrderFulfillParamsDTO first = params.getAllValues().get(0);
        OrderFulfillParamsDTO second = params.getAllValues().get(1);
        assertThat(first).extracting(OrderFulfillParamsDTO::getFulfillmentMode, OrderFulfillParamsDTO::getLogisticsId, OrderFulfillParamsDTO::getLogisticsNo)
                .containsExactly("SPLIT", "LOG-SF", "SF1");
        assertThat(lines(first)).containsOnly(Map.entry("I-P", 1), Map.entry("I-E", 1));
        assertThat(second).extracting(OrderFulfillParamsDTO::getFulfillmentMode, OrderFulfillParamsDTO::getLogisticsNo).containsExactly("SPLIT", "SF2");
        assertThat(lines(second)).containsOnly(Map.entry("I-P", 1), Map.entry("I-Q", 1));
        verify(tasks, never()).open(any(), any(), anyString(), any(), any());
    }

    /** 回写重放（上次只发出了第一个包）：已在壳里的运单不再发，订单此时是部分发货。 */
    @Test
    void splitModeReplaySkipsWaybillsAlreadyShippedInTheShell() {
        splitOrder();
        when(orders.getBySn("O-1")).thenReturn(order("PARTS_DELIVERED"));
        List<OrderItem> after = List.of(item("I-P", "SN-P", "S-P", 2), item("I-Q", "SN-Q", "S-P", 1), item("I-E", "SN-E", "S-A", 1));
        after.get(0).setDeliverNumber(1);
        after.get(2).setDeliverNumber(1);
        when(items.getByOrderSn("O-1")).thenReturn(after);
        OrderPackage shipped = new OrderPackage();
        shipped.setLogisticsCode("LOG-SF");
        shipped.setLogisticsNo("SF1");
        when(packages.orderPackageList("O-1")).thenReturn(List.of(shipped));

        shipping.markDelivered("O-1");

        ArgumentCaptor<OrderFulfillParamsDTO> params = ArgumentCaptor.forClass(OrderFulfillParamsDTO.class);
        verify(orders).fulfillOrder(params.capture());
        assertThat(params.getValue().getLogisticsNo()).isEqualTo("SF2");
        assertThat(lines(params.getValue())).containsOnly(Map.entry("I-P", 1), Map.entry("I-Q", 1));
    }

    @Test
    void partlyShippedOrderUnderManualModeIsLeftToOperators() {
        when(orders.getBySn("O-1")).thenReturn(order("PARTS_DELIVERED"));

        shipping.markDelivered("O-1");

        verify(orders, never()).fulfillOrder(any());
        verify(tasks, never()).open(any(), any(), anyString(), any(), any());
    }

    @Test
    void splitModeStillShipsSingleWaybillAsWhole() {
        when(settings.get(FulfillmentSettings.SHIPPING_MULTI_WAYBILL_MODE)).thenReturn(MultiWaybillMode.SPLIT);
        physicalOrder(List.of(card(1, "PHYSICAL", "LOG-SF", "SF1"), card(2, "ESIM", null, null)));

        shipping.markDelivered("O-1");

        ArgumentCaptor<OrderFulfillParamsDTO> params = ArgumentCaptor.forClass(OrderFulfillParamsDTO.class);
        verify(orders).fulfillOrder(params.capture());
        assertThat(params.getValue().getFulfillmentMode()).isEqualTo("WHOLE");
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
