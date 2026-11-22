package com.liroam.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.spi.WriteBack.DeliveredCard;
import com.liroam.channel.spi.WriteBackOutcome;
import com.liroam.channel.testing.ChannelFixture;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.testing.MutableClock;

/** 交码后回传：行内全部卡交码才回传；成功 → DONE（整单都回传完 → 渠道单 DONE）；可重试失败退避重试，超次数或被拒转人工，可人工重试。 */
class ChannelWriteBacksTest {

    private static ChannelFixture f;
    private static ChannelOrders orders;
    private static ChannelWriteBacks writeBacks;

    @BeforeAll
    static void start() {
        f = ChannelFixture.start("channel_writebacks");
        orders = f.bean(ChannelOrders.class);
        writeBacks = f.bean(ChannelWriteBacks.class);
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    private String lineStatus(String externalOrderId) {
        return f.h.jdbc.queryForObject("SELECT l.writeback_status FROM lr_channel_order_line l JOIN lr_channel_order o ON o.id = l.channel_order_id "
                + "WHERE o.external_order_id = ?", String.class, externalOrderId);
    }

    private long lineId(String externalOrderId) {
        return f.h.jdbc.queryForObject("SELECT l.id FROM lr_channel_order_line l JOIN lr_channel_order o ON o.id = l.channel_order_id "
                + "WHERE o.external_order_id = ?", Long.class, externalOrderId);
    }

    /** 一行 2 张卡的渠道单；{@code poolCards} 张池卡带码。 */
    private void orderOfTwo(String id, int poolCards) {
        if (poolCards > 0) {
            f.h.importPoolA(poolCards, true);
        }
        f.adapter.addOrder(id, MutableClock.START, "13800138000", new ExternalOrderLine("1", "EXT-A", 2, 9800));
        orders.pullNow(f.channelId, "op");
        f.h.drain();
    }

    @Test
    void lineIsWrittenBackOnlyAfterEveryCardOfItHasItsCredential() {
        orderOfTwo("O-1", 1);

        assertThat(lineStatus("O-1")).as("只有一张交码").isEqualTo("PENDING");
        assertThat(writeBacks.runOnce(10)).isZero();
        assertThat(f.adapter.writeBacks).isEmpty();
    }

    @Test
    void deliveredLineIsWrittenBackWithEveryCardCredentialAndOrderCompletes() {
        orderOfTwo("O-2", 2);
        assertThat(lineStatus("O-2")).isEqualTo("READY");

        assertThat(writeBacks.runOnce(10)).isEqualTo(1);

        assertThat(lineStatus("O-2")).isEqualTo("DONE");
        assertThat(f.orderStatus("O-2")).isEqualTo("DONE");
        assertThat(f.adapter.writeBacks).hasSize(1);
        var call = f.adapter.writeBacks.getFirst();
        assertThat(call.ref().externalOrderId()).isEqualTo("O-2");
        assertThat(call.ref().lineNo()).isEqualTo("1");
        assertThat(call.ref().externalSkuId()).isEqualTo("EXT-A");
        List<DeliveredCard> cards = call.writeBack().cards();
        assertThat(cards).hasSize(2).allSatisfy(card -> {
            assertThat(card.credentialKind()).isEqualTo("LPA");
            assertThat(card.activationCode()).startsWith("LPA:1$pool.example$");
            assertThat(card.form()).isEqualTo("ESIM");
            assertThat(card.toString()).doesNotContain("pool.example");
        });
        assertThat(f.h.count("lr_sensitive_access_log WHERE operator_type = 'SYSTEM'")).isEqualTo(2);
        assertThat(writeBacks.runOnce(10)).as("已回传不再回传").isZero();
    }

    @Test
    void retryableFailureBacksOffThenSucceeds() {
        orderOfTwo("O-3", 2);
        f.adapter.nextWriteBacks(new WriteBackOutcome.Retryable("渠道 503"));

        writeBacks.runOnce(10);

        assertThat(lineStatus("O-3")).isEqualTo("FAILED");
        assertThat(f.h.jdbc.queryForObject("SELECT CONCAT(writeback_attempts, '|', writeback_error) FROM lr_channel_order_line", String.class))
                .isEqualTo("1|渠道 503");
        assertThat(writeBacks.runOnce(10)).as("退避未到").isZero();

        f.h.clock.advance(Duration.ofMinutes(1));
        assertThat(writeBacks.runOnce(10)).isEqualTo(1);
        assertThat(lineStatus("O-3")).isEqualTo("DONE");
        assertThat(f.adapter.writeBacks).hasSize(2);
    }

    @Test
    void exhaustedRetriesOrRejectionGoToManualAndCanBeRetriedByOperator() {
        f.bean(Settings.class).set(ChannelSettings.WRITEBACK_MAX_ATTEMPTS, new SettingScope(SettingScope.Type.CHANNEL, f.channelId), 2, "op");
        orderOfTwo("O-4", 2);
        f.adapter.nextWriteBacks(new WriteBackOutcome.Retryable("超时"), new WriteBackOutcome.Retryable("超时"));

        writeBacks.runOnce(10);
        f.h.clock.advance(Duration.ofHours(1));
        writeBacks.runOnce(10);

        assertThat(lineStatus("O-4")).isEqualTo("MANUAL");
        long line = lineId("O-4");
        assertThat(f.h.jdbc.queryForList("SELECT CONCAT(kind, '|', ref_type, '|', ref_id) FROM lr_manual_task", String.class))
                .containsExactly("CHANNEL_WRITEBACK_FAILED|CHANNEL_ORDER_LINE|" + line);
        f.h.clock.advance(Duration.ofHours(1));
        assertThat(writeBacks.runOnce(10)).as("人工态不自动重试").isZero();

        writeBacks.retry(line, "op");
        assertThat(writeBacks.runOnce(10)).isEqualTo(1);
        assertThat(lineStatus("O-4")).isEqualTo("DONE");
        assertThat(f.h.jdbc.queryForObject("SELECT status FROM lr_manual_task", String.class)).isEqualTo("DONE");
        assertThat(f.h.count("lr_audit_log WHERE action = 'CHANNEL_WRITEBACK_RETRY'")).isEqualTo(1);
        assertThatThrownBy(() -> writeBacks.retry(line, "op")).isInstanceOf(UserFacingException.class).hasMessage("只有转人工的回传可以重试");
    }

    @Test
    void rejectionGoesStraightToManual() {
        orderOfTwo("O-5", 2);
        f.adapter.nextWriteBacks(new WriteBackOutcome.Rejected("ORDER_CANCELLED", "订单已取消"));

        writeBacks.runOnce(10);

        assertThat(lineStatus("O-5")).isEqualTo("MANUAL");
        assertThat(f.h.jdbc.queryForObject("SELECT writeback_error FROM lr_channel_order_line", String.class)).contains("订单已取消");
        assertThat(f.h.count("lr_manual_task WHERE kind = 'CHANNEL_WRITEBACK_FAILED'")).isEqualTo(1);
    }

    @Test
    void sweepCatchesLinesCompletedOutsideTheDeliveryEvent() {
        orderOfTwo("O-6", 2);
        f.h.jdbc.update("UPDATE lr_channel_order_line SET writeback_status = 'PENDING', writeback_next_at = NULL");

        assertThat(writeBacks.runOnce(10)).isEqualTo(1);
        assertThat(lineStatus("O-6")).isEqualTo("DONE");
    }
}
