package com.liroam.shell.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBinding.ListingState;
import com.liroam.catalog.SkuBinding.SkuKind;
import com.liroam.catalog.SkuBindings;
import com.liroam.fulfillment.FulfillmentRejected;
import com.liroam.fulfillment.FulfillmentRejected.Reason;
import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.FulfillmentRules;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.pricing.snapshot.OrderLineSnapshots;
import com.liroam.pricing.snapshot.SnapshotRequest;

import cn.lili.common.exception.ServiceException;
import cn.lili.modules.order.cart.entity.dto.TradeDTO;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.enums.OrderTypeEnum;
import cn.lili.modules.order.order.entity.vo.OrderVO;
import cn.lili.modules.order.order.event.TradeIntoDbEvent;
import cn.lili.modules.order.order.service.OrderItemService;

/** H2：交易落库时校验可售、绑定加包选卡、写订单行快照；任何不通过都抛 {@link ServiceException} 阻止下单。 */
class TradeOrderPreparationTest {

    private final OrderItemService items = mock(OrderItemService.class);
    private final SkuBindings bindings = mock(SkuBindings.class);
    private final FulfillmentRules rules = mock(FulfillmentRules.class);
    private final TopUpIntents intents = mock(TopUpIntents.class);
    private final OrderLineSnapshots snapshots = mock(OrderLineSnapshots.class);
    private final TradeOrderPreparation preparation = new TradeOrderPreparation(items, bindings, rules, intents, snapshots);
    private final TradeIntoDbListener listener = new TradeIntoDbListener(preparation);

    private static OrderItem item(String sn, String sku, int num, double unit, double flow) {
        OrderItem i = new OrderItem();
        i.setSn(sn);
        i.setSkuId(sku);
        i.setNum(num);
        i.setUnitPrice(unit);
        i.setFlowPrice(flow);
        i.setOrderSn("O-1");
        return i;
    }

    private static SkuBinding binding(long id, String sku, SkuKind kind) {
        return new SkuBinding(id, sku, "G", kind, 1, 10, 100L, null, "ESIM", 1, 1, ListingState.LISTED, false);
    }

    private static TradeIntoDbEvent trade(String memberId, String... orderSns) {
        return trade(OrderTypeEnum.NORMAL, memberId, orderSns);
    }

    private static TradeIntoDbEvent trade(OrderTypeEnum orderType, String memberId, String... orderSns) {
        TradeDTO dto = new TradeDTO();
        dto.setMemberId(memberId);
        dto.setOrderVO(java.util.Arrays.stream(orderSns).map(sn -> {
            OrderVO vo = new OrderVO();
            vo.setSn(sn);
            vo.setOrderType(orderType.name());
            // 事件里的 OrderVO.orderItems 是另一批 new 出来的对象，行号与落库行不同，必须按订单号回查落库行
            vo.setOrderItems(List.of(item("I-NOT-PERSISTED", "S-CARD", 1, 1, 1)));
            return vo;
        }).toList());
        return new TradeIntoDbEvent(new Object(), dto);
    }

    @Test
    void newCardLinesAreVerifiedAndSnapshottedByPersistedLineSn() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-1", "S-CARD", 2, 29.9, 59.8), item("I-2", "S-PLAIN", 1, 5, 5)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-CARD", binding(11, "S-CARD", SkuKind.NEW_CARD)));

        listener.onTradeIntoDb(trade("m1", "O-1"));

        verify(rules).verify(new FulfillmentRequest(SourceType.SHOP, "O-1", "I-1", 11, 2, "m1", null, null));
        verify(snapshots).record(new SnapshotRequest(SnapshotRequest.SourceType.SHOP, "I-1", "O-1", 11, 2990, 5980, 2, null));
        verify(intents, never()).bind(any(), any(), any());
    }

    @Test
    void topUpLineBindsTheOpenIntentAndSnapshotsTheCard() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-3", "S-TOPUP", 1, 9.9, 9.9)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-TOPUP", binding(13, "S-TOPUP", SkuKind.TOP_UP)));
        when(intents.bind("O-1", "m1", "S-TOPUP")).thenReturn(Optional.of(101L));

        listener.onTradeIntoDb(trade("m1", "O-1"));

        verify(rules).verify(new FulfillmentRequest(SourceType.TOPUP, "O-1", "I-3", 13, 1, "m1", 101L, null));
        ArgumentCaptor<SnapshotRequest> snap = ArgumentCaptor.forClass(SnapshotRequest.class);
        verify(snapshots).record(snap.capture());
        assertThat(snap.getValue().topupCardAssetId()).isEqualTo(101L);
        assertThat(snap.getValue().sourceType()).isEqualTo(SnapshotRequest.SourceType.TOPUP);
    }

    @Test
    void topUpWithoutIntentBlocksTheOrderWithTheSharedMessage() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-3", "S-TOPUP", 1, 9.9, 9.9)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-TOPUP", binding(13, "S-TOPUP", SkuKind.TOP_UP)));
        when(intents.bind(any(), any(), any())).thenReturn(Optional.empty());
        doThrow(new FulfillmentRejected(Reason.TOPUP_CARD_REQUIRED, "I-3")).when(rules)
                .verify(new FulfillmentRequest(SourceType.TOPUP, "O-1", "I-3", 13, 1, "m1", null, null));

        assertThatThrownBy(() -> listener.onTradeIntoDb(trade("m1", "O-1")))
                .isInstanceOf(ServiceException.class).hasMessageContaining(Reason.TOPUP_CARD_REQUIRED.userMessage());
        verify(snapshots, never()).record(any());
    }

    @Test
    void topUpQuantityOtherThanOneIsRejectedByTheSameRules() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-3", "S-TOPUP", 2, 9.9, 19.8)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-TOPUP", binding(13, "S-TOPUP", SkuKind.TOP_UP)));
        when(intents.bind("O-1", "m1", "S-TOPUP")).thenReturn(Optional.of(101L));
        doThrow(new FulfillmentRejected(Reason.TOPUP_QTY_NOT_ONE, "qty=2")).when(rules).verify(any());

        assertThatThrownBy(() -> listener.onTradeIntoDb(trade("m1", "O-1")))
                .isInstanceOf(ServiceException.class).hasMessageContaining(Reason.TOPUP_QTY_NOT_ONE.userMessage());
    }

    @Test
    void unlistedSkuBlocksTheWholeTrade() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-1", "S-CARD", 1, 10, 10)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-CARD", binding(11, "S-CARD", SkuKind.NEW_CARD)));
        doThrow(new FulfillmentRejected(Reason.SKU_NOT_LISTED, "x")).when(rules).verify(any());

        assertThatThrownBy(() -> listener.onTradeIntoDb(trade("m1", "O-1"))).isInstanceOf(ServiceException.class);
        verify(snapshots, never()).record(any());
    }

    @Test
    void unexpectedFailureAlsoSurfacesAsServiceException() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-1", "S-CARD", 1, 10, 10)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-CARD", binding(11, "S-CARD", SkuKind.NEW_CARD)));
        doThrow(new IllegalStateException("db down")).when(snapshots).record(any());

        assertThatThrownBy(() -> listener.onTradeIntoDb(trade("m1", "O-1"))).isInstanceOf(ServiceException.class);
    }

    /** 壳的核销型虚拟订单（cartType=VIRTUAL → orderType VIRTUAL）付款后进「待核销」，不进待发货，H5 无法收尾。 */
    @Test
    void travelCardInNonNormalOrderBlocksTheTrade() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-1", "S-CARD", 1, 10, 10)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-CARD", binding(11, "S-CARD", SkuKind.NEW_CARD)));

        assertThatThrownBy(() -> listener.onTradeIntoDb(trade(OrderTypeEnum.VIRTUAL, "m1", "O-1")))
                .isInstanceOf(ServiceException.class).hasMessageContaining(TradeOrderPreparation.ORDER_TYPE_MESSAGE);
        verify(rules, never()).verify(any());
        verify(snapshots, never()).record(any());
    }

    @Test
    void nonNormalOrderWithoutTravelCardsIsUntouched() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-2", "S-PLAIN", 1, 5, 5)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of());

        listener.onTradeIntoDb(trade(OrderTypeEnum.VIRTUAL, "m1", "O-1"));

        verify(rules, never()).verify(any());
    }

    @Test
    void tradeWithoutTravelCardsIsUntouched() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-2", "S-PLAIN", 1, 5, 5)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of());

        listener.onTradeIntoDb(trade("m1", "O-1"));
        listener.onTradeIntoDb(new TradeIntoDbEvent(new Object(), new TradeDTO()));

        verify(rules, never()).verify(any());
        verify(snapshots, never()).record(any());
    }
}
