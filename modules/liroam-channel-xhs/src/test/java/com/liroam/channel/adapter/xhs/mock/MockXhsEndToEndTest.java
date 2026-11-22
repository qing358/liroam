package com.liroam.channel.adapter.xhs.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.channel.ChannelClaims;
import com.liroam.channel.ChannelOrders;
import com.liroam.channel.ChannelWriteBacks;
import com.liroam.channel.adapter.mock.SimulatedChannel.OrderRequest;
import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.channel.testing.ChannelFixture;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardView;

/**
 * 新渠道只加适配器即可进「我的卡」：只注册 {@code mock-xhs} Bean、配 {@code lr_channel} 行与 SKU 映射，
 * 渠道核心、履约、台账不改，造单 → 拉单入履约 → 交码 → 回传 → 买家认领 → 「我的卡」出现这张卡。
 */
class MockXhsEndToEndTest {

    private static ChannelFixture f;
    private static MockXhsChannelAdapter xhs;
    private long xhsChannel;

    @BeforeAll
    static void start() {
        f = ChannelFixture.start("channel_xhs", Map.of(), ctx -> ctx.registerBean(MockXhsChannelAdapter.ADAPTER_KEY, ChannelAdapter.class,
                () -> xhs = new MockXhsChannelAdapter(ctx.getBean(Clock.class))));
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
        xhs.reset();
        xhsChannel = f.insertChannel("XHS", MockXhsChannelAdapter.ADAPTER_KEY, true, true);
        f.h.jdbc.update("INSERT INTO lr_channel_sku_mapping (channel_id, external_sku_id, li_goods_sku_id) VALUES (?, 'XHS-SKU-1001', 'SKU-A')",
                xhsChannel);
    }

    @Test
    void mockOrderIsPulledFulfilledWrittenBackAndClaimedIntoMyCards() {
        f.h.importPoolA(1, true);
        String orderId = xhs.createOrder(new OrderRequest("XHS-SKU-1001", 1, "59.00", "13800138000")).orderId();

        assertThat(f.bean(ChannelOrders.class).pullNow(xhsChannel, "op").created()).isEqualTo(1);
        f.h.drain();
        assertThat(f.bean(ChannelWriteBacks.class).runOnce(10)).isEqualTo(1);
        assertThat(xhs.orders()).singleElement().satisfies(o -> assertThat(o.writeBacks()).hasSize(1));
        assertThat(f.h.jdbc.queryForObject("SELECT CONCAT(status, '|', paid_fen) FROM lr_channel_order WHERE channel_id = ? AND external_order_id = ?",
                String.class, xhsChannel, orderId)).isEqualTo("DONE|5900");

        f.phones.bind("m1", "13800138000");
        assertThat(f.bean(ChannelClaims.class).claim("m1", orderId, "13800138000").cardCount()).isEqualTo(1);

        List<CardView> mine = f.bean(CardQueries.class).listForMember("m1");
        assertThat(mine).extracting(CardView::id).containsExactlyElementsOf(f.cardsOf(orderId));
        assertThat(mine).singleElement().satisfies(card -> assertThat(card.status()).isEqualTo("DELIVERED"));
    }
}
