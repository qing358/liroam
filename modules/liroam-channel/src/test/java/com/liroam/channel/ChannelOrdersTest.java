package com.liroam.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.channel.ChannelOrders.PullResult;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.testing.ChannelFixture;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.testing.MutableClock;

/** 渠道入单：拉单幂等（CT-CH-01）、按映射进唯一履约入口与订单行快照、映射不到 / 不可收单转人工并可重放、不建壳订单、拉单节拍与游标。 */
class ChannelOrdersTest {

    private static ChannelFixture f;
    private static ChannelOrders orders;

    private static final Instant T0 = MutableClock.START;

    @BeforeAll
    static void start() {
        f = ChannelFixture.start("channel_orders");
        orders = f.bean(ChannelOrders.class);
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    @Test
    void ct_ch_01_repeatedPullsLandOneOrderAndOneFulfillmentLinePerLine() {
        f.adapter.addOrder("O-1", T0, "13800138000", new ExternalOrderLine("1", "EXT-A", 2, 9000), new ExternalOrderLine("2", "EXT-B", 1, 9900));

        PullResult first = orders.pullNow(f.channelId, "op");
        PullResult second = orders.pullNow(f.channelId, "op");

        assertThat(first).extracting(PullResult::pulled, PullResult::created, PullResult::manual).containsExactly(1, 1, 0);
        assertThat(second).extracting(PullResult::pulled, PullResult::created, PullResult::manual).containsExactly(1, 0, 0);
        assertThat(f.h.count("lr_channel_order")).isEqualTo(1);
        assertThat(f.h.count("lr_channel_order_line")).isEqualTo(2);
        assertThat(f.orderStatus("O-1")).isEqualTo("FULFILLING");
        long orderId = f.orderId("O-1");
        assertThat(f.h.jdbc.queryForList("""
                SELECT CONCAT(l.source_type, '|', l.source_ref, '|', l.source_line_ref, '|', l.qty, '|', l.sku_binding_id, '|',
                              COALESCE(l.owner_member_id, '-'), '|', l.channel_order_line_id = col.id)
                  FROM lr_fulfillment_line l JOIN lr_channel_order_line col ON col.id = l.channel_order_line_id ORDER BY l.id
                """, String.class)).containsExactly(
                "CHANNEL|O-1|CH_A:O-1:1|2|" + f.c.skuANew + "|-|1",
                "CHANNEL|O-1|CH_A:O-1:2|1|" + f.c.skuBNew + "|-|1");
        assertThat(f.h.jdbc.queryForList("SELECT li_goods_sku_id FROM lr_channel_order_line WHERE channel_order_id = ? ORDER BY id", String.class,
                orderId)).containsExactly("SKU-A", "SKU-B");
        assertThat(f.h.jdbc.queryForList("""
                SELECT CONCAT(source_type, '|', source_line_ref, '|', unit_price_fen, '|', paid_fen, '|', qty, '|', COALESCE(li_order_sn, '-'))
                  FROM lr_order_line_snapshot ORDER BY id
                """, String.class)).as("售价取渠道实付").containsExactly("CHANNEL|CH_A:O-1:1|4500|9000|2|-", "CHANNEL|CH_A:O-1:2|9900|9900|1|-");

        Map<String, Object> row = f.h.jdbc.queryForMap("SELECT paid_fen, buyer_contact_hash, buyer_contact_cipher, key_version, "
                + "CAST(raw_json AS CHAR) AS raw FROM lr_channel_order WHERE id = ?", orderId);
        assertThat(row.get("paid_fen")).isEqualTo(18900L);
        assertThat((byte[]) row.get("buyer_contact_hash")).isEqualTo(f.bean(SensitiveCipher.class).hash(SensitiveField.BUYER_CONTACT, "13800138000"));
        assertThat(f.bean(SensitiveCipher.class).open(SensitiveField.BUYER_CONTACT,
                new SensitiveCipher.Sealed(((Number) row.get("key_version")).intValue(), (byte[]) row.get("buyer_contact_cipher")))).isEqualTo("13800138000");
        assertThat((String) row.get("raw")).doesNotContain("13800138000");
    }

    @Test
    void channelOrderDrivesTheLedgerToOwnerlessCardsWithoutAnyShellOrder() {
        f.h.importPoolA(2, true);
        f.adapter.addOrder("O-2", T0, "13800138000", new ExternalOrderLine("1", "EXT-A", 2, 9000));
        orders.pullNow(f.channelId, "op");

        f.h.drain();

        List<Long> cards = f.cardsOf("O-2");
        assertThat(cards).hasSize(2);
        assertThat(f.h.jdbc.queryForList("SELECT CONCAT(status, '|', COALESCE(owner_member_id, '-')) FROM lr_card_asset ORDER BY id", String.class))
                .containsExactly("DELIVERED|-", "DELIVERED|-");
        assertThat(f.h.jdbc.queryForObject("SELECT status FROM lr_fulfillment_line WHERE source_ref = 'O-2'", String.class)).isEqualTo("COMPLETED");
        assertThat(f.h.shell.calls()).as("渠道单不回写壳发货").isEmpty();
        assertThat(f.h.count("lr_shell_delivery")).isZero();
    }

    @Test
    void unmappedSkuParksTheOrderForManualMappingThenReplayFulfillsIt() {
        f.adapter.addOrder("O-3", T0, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900), new ExternalOrderLine("2", "EXT-NEW", 1, 5900));

        PullResult result = orders.pullNow(f.channelId, "op");

        assertThat(result.manual()).isEqualTo(1);
        long orderId = f.orderId("O-3");
        assertThat(f.orderStatus("O-3")).isEqualTo("MANUAL");
        assertThat(f.h.count("lr_fulfillment_line")).as("整单不进履约").isZero();
        assertThat(f.h.count("lr_order_line_snapshot")).isZero();
        assertThat(f.h.jdbc.queryForList("SELECT CONCAT(kind, '|', ref_type, '|', ref_id, '|', status) FROM lr_manual_task", String.class))
                .containsExactly("UNMAPPED_CHANNEL_SKU|CHANNEL_ORDER|" + orderId + "|OPEN");
        assertThat(f.h.jdbc.queryForObject("SELECT note FROM lr_manual_task", String.class)).contains("EXT-NEW");
        assertThat(orders.pullNow(f.channelId, "op").manual()).as("重复拉单不重开待办").isZero();
        assertThat(f.h.count("lr_manual_task")).isEqualTo(1);

        assertThatThrownBy(() -> orders.replay(orderId, "op")).isInstanceOf(UserFacingException.class).hasMessageContaining("EXT-NEW");

        f.bean(ChannelSkuMappings.class).create(f.channelId, "EXT-NEW", "SKU-B", "op");
        assertThat(orders.replay(orderId, "op").status()).isEqualTo("FULFILLING");

        assertThat(f.h.count("lr_fulfillment_line WHERE source_type = 'CHANNEL'")).isEqualTo(2);
        assertThat(f.h.jdbc.queryForObject("SELECT status FROM lr_manual_task", String.class)).isEqualTo("DONE");
        assertThatThrownBy(() -> orders.replay(orderId, "op")).isInstanceOf(UserFacingException.class).hasMessage("只有待人工处理的渠道单可以重放");
    }

    @Test
    void mappedSkuThatCannotBeSoldParksTheWholeOrderWithoutPartialFulfillment() {
        f.h.jdbc.update("UPDATE lr_sku_binding SET listing_state = 'DELISTED' WHERE id = ?", f.c.skuBNew);
        f.adapter.addOrder("O-4", T0, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900), new ExternalOrderLine("2", "EXT-B", 1, 9900));

        orders.pullNow(f.channelId, "op");

        assertThat(f.orderStatus("O-4")).isEqualTo("MANUAL");
        assertThat(f.h.count("lr_fulfillment_line")).isZero();
        assertThat(f.h.count("lr_order_line_snapshot")).isZero();
        assertThat(f.h.jdbc.queryForList("SELECT kind FROM lr_manual_task", String.class)).containsExactly("CHANNEL_ORDER_REJECTED");
        assertThat(f.h.jdbc.queryForObject("SELECT last_error FROM lr_channel_order", String.class)).contains("已下架");

        f.h.jdbc.update("UPDATE lr_sku_binding SET listing_state = 'LISTED' WHERE id = ?", f.c.skuBNew);
        assertThat(orders.replay(f.orderId("O-4"), "op").status()).isEqualTo("FULFILLING");
        assertThat(f.h.count("lr_fulfillment_line")).isEqualTo(2);
    }

    @Test
    void scheduledPullHonoursIntervalAndCursorAndSkipsDisabledChannels() {
        long off = f.insertChannel("CH_OFF", ChannelFixture.ADAPTER, false, false);
        f.adapter.addOrder("O-5", T0, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));

        assertThat(orders.pullDue()).isEqualTo(1);
        assertThat(f.adapter.pulls).containsExactly(T0.minus(Duration.ofDays(1)));
        assertThat(orders.pullDue()).as("间隔未到").isZero();

        f.h.clock.advance(Duration.ofMinutes(5));
        assertThat(orders.pullDue()).isEqualTo(1);
        assertThat(f.adapter.pulls).as("下一次从上次拉单时刻回看重叠窗口").hasSize(2).last().isEqualTo(T0.minus(ChannelOrders.PULL_OVERLAP));
        assertThat(f.h.count("lr_channel_order")).isEqualTo(1);
        assertThat(f.h.count("lr_channel_order WHERE channel_id = ?", off)).isZero();
        assertThat(f.h.count("lr_audit_log WHERE action = 'SETTING_SET'")).as("游标不是配置，不刷审计").isZero();

        f.h.jdbc.update("UPDATE lr_channel SET pull_cron = '0 0 * * * *' WHERE id = ?", f.channelId);
        f.h.clock.advance(Duration.ofMinutes(5));
        assertThat(orders.pullDue()).as("整点 cron：12:10 → 下一次 13:00").isZero();
        f.h.clock.set(Instant.parse("2026-11-22T05:00:00Z"));
        assertThat(orders.pullDue()).isEqualTo(1);
    }

    @Test
    void pullIntervalAcceptsMinutesDurationsAndCron() {
        Instant last = Instant.parse("2026-11-22T04:07:00Z");
        assertThat(PullSchedule.parse("5m").nextAfter(last)).isEqualTo(last.plus(Duration.ofMinutes(5)));
        assertThat(PullSchedule.parse("PT30S").nextAfter(last)).isEqualTo(last.plusSeconds(30));
        assertThat(PullSchedule.parse(null).nextAfter(last)).isEqualTo(last.plus(Duration.ofMinutes(5)));
        assertThat(PullSchedule.parse("0 */10 * * * *").nextAfter(last)).isEqualTo(Instant.parse("2026-11-22T04:10:00Z"));
        for (String bad : Arrays.asList("0m", "abc", "-5m")) {
            assertThatThrownBy(() -> PullSchedule.parse(bad)).as(bad).isInstanceOf(UserFacingException.class);
        }
    }
}
