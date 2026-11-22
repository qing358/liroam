package com.liroam.shell.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.TopUpIntents;

import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.service.OrderItemService;

class PaidOrderFulfillmentTest {

    private final OrderItemService items = mock(OrderItemService.class);
    private final SkuBindings bindings = mock(SkuBindings.class);
    private final TopUpIntents intents = mock(TopUpIntents.class);
    private final FulfillmentEntry entry = mock(FulfillmentEntry.class);
    private final PaidOrderFulfillment fulfillment = new PaidOrderFulfillment(items, bindings, intents, entry);

    private static OrderItem item(String sn, String sku, int num) {
        OrderItem i = new OrderItem();
        i.setSn(sn);
        i.setSkuId(sku);
        i.setNum(num);
        i.setOrderSn("O-1");
        return i;
    }

    private static SkuBinding binding(long id, String sku, SkuKind kind) {
        return new SkuBinding(id, sku, "G", kind, 1, 10, 100L, null, "ESIM", 1, 1, ListingState.LISTED, false, SkuBinding.KycGateMode.NONE);
    }

    @Test
    void onlyBoundItemsReachTheEntryWithTheirOwnLineRef() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-1", "S-CARD", 3), item("I-2", "S-PLAIN", 1), item("I-3", "S-TOPUP", 1)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-CARD", binding(11, "S-CARD", SkuKind.NEW_CARD),
                "S-TOPUP", binding(13, "S-TOPUP", SkuKind.TOP_UP)));
        when(intents.boundCard("O-1", "S-TOPUP")).thenReturn(Optional.of(101L));

        assertThat(fulfillment.fulfill("O-1", "m1")).isEqualTo(2);

        ArgumentCaptor<FulfillmentRequest> captor = ArgumentCaptor.forClass(FulfillmentRequest.class);
        verify(entry, times(2)).accept(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(
                new FulfillmentRequest(SourceType.SHOP, "O-1", "I-1", 11, 3, "m1", null, null),
                new FulfillmentRequest(SourceType.TOPUP, "O-1", "I-3", 13, 1, "m1", 101L, null));
    }

    @Test
    void topUpWithoutIntentIsPassedOnSoTheRulesRejectIt() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-3", "S-TOPUP", 1)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of("S-TOPUP", binding(13, "S-TOPUP", SkuKind.TOP_UP)));
        when(intents.boundCard(any(), any())).thenReturn(Optional.empty());

        fulfillment.fulfill("O-1", "m1");

        verify(entry).accept(new FulfillmentRequest(SourceType.TOPUP, "O-1", "I-3", 13, 1, "m1", null, null));
    }

    @Test
    void orderWithoutTravelCardsIsIgnored() {
        when(items.getByOrderSn("O-1")).thenReturn(List.of(item("I-2", "S-PLAIN", 1)));
        when(bindings.findByShellSkuIds(anyCollection())).thenReturn(Map.of());

        assertThat(fulfillment.fulfill("O-1", "m1")).isZero();
        assertThat(fulfillment.fulfill("O-EMPTY", "m1")).isZero();
        verify(entry, never()).accept(any());
    }
}
