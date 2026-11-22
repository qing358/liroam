package com.liroam.fulfillment.poll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;

/** 轮询分层、用量镜像（套餐 READY→IN_USE/EXPIRED/DEPLETED、卡 IN_USE/EXPIRED/停用/终止）、按需刷新冷却。 */
class PollTest {

    private static final Instant SEEDED_EXPIRY = Instant.parse("2026-12-31T15:59:59Z");

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static PollScheduler scheduler;
    private static CardRefresh refresh;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_poll");
        c = h.catalog;
        scheduler = h.bean(PollScheduler.class);
        refresh = h.bean(CardRefresh.class);
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    private long deliveredCardB(String order) {
        long card = h.acceptCard(order, c.skuBNew, "m1");
        h.drain();
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        return card;
    }

    private String upstreamRef(long card) {
        return h.jdbc.queryForObject("SELECT upstream_ref FROM lr_card_asset WHERE id = ?", String.class, card);
    }

    private Map<String, Object> card(long id) {
        return h.jdbc.queryForMap("SELECT status, poll_tier, next_poll_at, last_polled_at, activated_at, expires_at FROM lr_card_asset WHERE id = ?", id);
    }

    private static Instant at(Object column) {
        return column == null ? null : UtcColumns.fromColumn((LocalDateTime) column);
    }

    private void pollNow() {
        scheduler.runOnce();
        h.drain();
    }

    @Test
    void cardMovesThroughTiersAsUsageIsMirroredUntilPollingStopsAfterExpiry() {
        long card = deliveredCardB("O-1");
        assertThat(card(card)).containsEntry("poll_tier", "NOT_ACTIVATED");
        assertThat(at(card(card).get("next_poll_at"))).isEqualTo(h.clock.instant().plus(Duration.ofHours(6)));
        assertThat(scheduler.runOnce()).as("未到点不排").isZero();

        h.upstreamB.seedUsage(new CardRef(card, null, upstreamRef(card)));
        h.clock.advance(Duration.ofHours(6));
        pollNow();

        Map<String, Object> plan = h.jdbc.queryForMap("SELECT status, quota_bytes, used_bytes, remaining_bytes, expires_at, expires_at_raw, "
                + "upstream_plan_ref, mirrored_at FROM lr_card_plan WHERE card_asset_id = ?", card);
        assertThat(plan).containsEntry("status", "IN_USE").containsEntry("quota_bytes", 2048L).containsEntry("used_bytes", 512L)
                .containsEntry("remaining_bytes", 1536L).containsEntry("expires_at_raw", SEEDED_EXPIRY.toString());
        assertThat(at(plan.get("expires_at"))).isEqualTo(SEEDED_EXPIRY);
        assertThat(card(card)).containsEntry("status", "IN_USE").containsEntry("poll_tier", "IN_USE");
        assertThat(at(card(card).get("activated_at"))).isEqualTo(h.clock.instant());
        assertThat(at(card(card).get("last_polled_at"))).isEqualTo(h.clock.instant());
        assertThat(at(card(card).get("expires_at"))).isEqualTo(SEEDED_EXPIRY);
        assertThat(at(card(card).get("next_poll_at"))).isEqualTo(h.clock.instant().plus(Duration.ofMinutes(30)));

        h.clock.set(Instant.parse("2026-12-31T00:00:00Z"));
        pollNow();
        assertThat(card(card)).containsEntry("poll_tier", "NEAR_EXPIRY");
        assertThat(at(card(card).get("next_poll_at"))).isEqualTo(h.clock.instant().plus(Duration.ofMinutes(15)));

        h.clock.set(Instant.parse("2027-01-01T00:00:00Z"));
        pollNow();
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE card_asset_id = ?", String.class, card)).isEqualTo("EXPIRED");
        assertThat(card(card)).containsEntry("status", "EXPIRED").containsEntry("poll_tier", "EXPIRED");
        assertThat(at(card(card).get("next_poll_at"))).isEqualTo(h.clock.instant().plus(Duration.ofDays(7)));

        h.clock.set(Instant.parse("2027-02-05T00:00:00Z"));
        assertThat(scheduler.runOnce()).as("到期 30 天后不再轮询").isZero();
        assertThat(card(card)).containsEntry("poll_tier", null).containsEntry("next_poll_at", null);
        assertThat(h.count("lr_supplier_call_log WHERE operation = 'QUERY'")).isEqualTo(3);
    }

    @Test
    void topUpBecomesReadyAndUnusedTopUpExpiresFromReady() {
        long card = deliveredCardB("O-2");
        long line = h.acceptTopUp("O-2T", "O-2T-1", c.skuBTopUp, "m1", card);
        h.drain();
        Map<String, Object> topUp = h.jdbc.queryForMap("SELECT id, status, upstream_plan_ref FROM lr_card_plan WHERE fulfillment_line_id = ?", line);
        assertThat(topUp).containsEntry("status", "READY");
        assertThat((String) topUp.get("upstream_plan_ref")).startsWith("PKG-");
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("COMPLETED");
        assertThat(h.shell.calls()).contains("O-2T");
        assertThat(at(card(card).get("next_poll_at"))).as("加包成功后下一轮就查").isEqualTo(h.clock.instant());

        h.clock.set(Instant.parse("2027-02-01T00:00:00Z"));
        pollNow();

        assertThat(h.status("lr_card_plan", ((Number) topUp.get("id")).longValue())).as("未激活即过期").isEqualTo("EXPIRED");
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE card_asset_id = ? AND kind = 'BASE'", String.class, card))
                .isEqualTo("READY");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
    }

    @Test
    void mirrorDepletesPlanAndFollowsUpstreamSuspendResumeTerminate() {
        long card = deliveredCardB("O-3");
        h.upstreamB.seedUsage(new CardRef(card, null, upstreamRef(card)));
        h.clock.advance(Duration.ofHours(6));
        pollNow();
        String planRef = h.jdbc.queryForObject("SELECT upstream_plan_ref FROM lr_card_plan WHERE card_asset_id = ?", String.class, card);
        UsageMirror mirror = h.bean(UsageMirror.class);
        TransactionTemplate tx = h.bean(TransactionTemplate.class);
        PlanUsage empty = new PlanUsage(planRef, 2048L, 2048L, 0L, null, null, null, null, null, null, null, SEEDED_EXPIRY, null, null);

        tx.executeWithoutResult(s -> mirror.apply(card, new UsageSnapshot(null, CardStatus.ACTIVE, List.of(empty), "{}"), h.clock.instant()));
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE card_asset_id = ?", String.class, card)).isEqualTo("DEPLETED");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("IN_USE");

        tx.executeWithoutResult(s -> mirror.apply(card, new UsageSnapshot(null, CardStatus.SUSPENDED, List.of(), "{}"), h.clock.instant()));
        assertThat(card(card)).containsEntry("status", "SUSPENDED").containsEntry("poll_tier", "SUSPENDED");

        tx.executeWithoutResult(s -> mirror.apply(card, new UsageSnapshot(null, CardStatus.ACTIVE, List.of(), "{}"), h.clock.instant()));
        assertThat(h.status("lr_card_asset", card)).as("恢复后回到使用中").isEqualTo("IN_USE");

        tx.executeWithoutResult(s -> mirror.apply(card, new UsageSnapshot(null, CardStatus.TERMINATED, List.of(), "{}"), h.clock.instant()));
        assertThat(card(card)).containsEntry("status", "TERMINATED").containsEntry("poll_tier", null);
    }

    @Test
    void onDemandRefreshHasCooldownAndOutranksQueuedPoll() {
        long card = deliveredCardB("O-4");
        h.clock.advance(Duration.ofHours(6));
        scheduler.runOnce();
        assertThat(h.command("QUERY", card)).containsEntry("priority", 2);

        assertThat(refresh.requestOnDemand(card)).isTrue();
        assertThat(h.count("lr_supplier_command WHERE type = 'QUERY'")).as("提优先级而不是再排一条").isEqualTo(1);
        assertThat(h.command("QUERY", card)).containsEntry("priority", 1);
        assertThat(refresh.requestOnDemand(card)).as("冷却中").isFalse();
        h.drain();

        h.clock.advance(Duration.ofMinutes(4));
        assertThat(refresh.requestOnDemand(card)).isFalse();
        h.clock.advance(Duration.ofMinutes(1));
        assertThat(refresh.requestOnDemand(card)).isTrue();
        assertThat(h.count("lr_supplier_command WHERE type = 'QUERY'")).isEqualTo(2);
    }

    @Test
    void onDemandRefreshIgnoresCardsNotYetDelivered() {
        long card = h.acceptCard("O-5", c.skuBNew, "m1");

        assertThat(refresh.requestOnDemand(card)).isFalse();
        assertThat(refresh.requestOnDemand(999_999)).isFalse();
        assertThat(h.count("lr_supplier_command WHERE type = 'QUERY'")).isZero();
    }
}
