package com.liroam.channel.adapter.jd.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.channel.ChannelClaims;
import com.liroam.channel.ChannelOrders;
import com.liroam.channel.ChannelReconciliation;
import com.liroam.channel.ChannelWriteBacks;
import com.liroam.channel.adapter.mock.SimulatedChannel.OrderRequest;
import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.channel.testing.ChannelFixture;
import com.liroam.fulfillment.DisplayTime;

/** 模拟京东跑通渠道带：造单 → 拉单入履约 → 交码 → 回传 → 买家认领 → 日对账（注入金额不符）。 */
class MockJdEndToEndTest {

    private static ChannelFixture f;
    private static MockJdChannelAdapter jd;
    private long jdChannel;

    @BeforeAll
    static void start() {
        f = ChannelFixture.start("channel_jd", Map.of(), ctx -> ctx.registerBean(MockJdChannelAdapter.ADAPTER_KEY, ChannelAdapter.class,
                () -> jd = new MockJdChannelAdapter(ctx.getBean(Clock.class))));
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
        jd.reset();
        jdChannel = f.insertChannel("JD", MockJdChannelAdapter.ADAPTER_KEY, true, true);
        f.h.jdbc.update("INSERT INTO lr_channel_sku_mapping (channel_id, external_sku_id, li_goods_sku_id) VALUES (?, 'JD-SKU-1001', 'SKU-A')",
                jdChannel);
    }

    @Test
    void mockOrderRunsThroughPullFulfilmentWriteBackClaimAndRecon() {
        f.h.importPoolA(1, true);
        String orderId = jd.createOrder(new OrderRequest("JD-SKU-1001", 1, "49.00", "13800138000")).orderId();

        assertThat(f.bean(ChannelOrders.class).pullNow(jdChannel, "op").created()).isEqualTo(1);
        f.h.drain();
        assertThat(f.bean(ChannelWriteBacks.class).runOnce(10)).isEqualTo(1);
        assertThat(jd.orders()).singleElement().satisfies(o -> assertThat(o.writeBacks()).hasSize(1));
        assertThat(f.h.jdbc.queryForObject("SELECT status FROM lr_channel_order WHERE external_order_id = ?", String.class, orderId)).isEqualTo("DONE");

        f.phones.bind("m1", "13800138000");
        assertThat(f.bean(ChannelClaims.class).claim("m1", orderId, "13800138000").cardCount()).isEqualTo(1);

        jd.adjustStatement(orderId, "39.00");
        LocalDate day = LocalDate.ofInstant(f.h.clock.instant(), DisplayTime.ZONE);
        assertThat(f.bean(ChannelReconciliation.class).run(jdChannel, day, "op").diffCount()).isEqualTo(1);
        assertThat(f.h.jdbc.queryForObject("SELECT CONCAT(diff_kind, '|', our_amount_minor, '|', their_amount_minor) FROM lr_recon_diff", String.class))
                .isEqualTo("AMOUNT|4900|3900");
    }
}
