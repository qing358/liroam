package com.liroam.fulfillment.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.catalog.CatalogSettings;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentRejected;
import com.liroam.fulfillment.FulfillmentRejected.Reason;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;

/** 卡型在同一投影上的差异：流量卡只出流量；语音短信卡多出语音/短信余量（先模板额度，镜像后取上游）；含号码 SKU 受全局可售闸。 */
class CardTypeViewTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static CardQueries queries;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_card_type_view");
        c = h.catalog;
        queries = h.bean(CardQueries.class);
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    @Test
    void dataOnlyCardShowsNoVoiceOrSms() {
        h.importPoolA(1, true);
        long card = h.acceptCard("O-1", c.skuANew, "m1");
        h.drain();

        CardView v = queries.getForMember(card, "m1").orElseThrow();

        assertThat(v).extracting(CardView::capabilityText, CardView::remainingText, CardView::voiceRemainingText, CardView::smsRemainingText)
                .containsExactly("流量", "3 GB", null, null);
        assertThat(v.plans()).extracting(CardView.PlanView::voiceRemainingText, CardView.PlanView::smsRemainingText).containsExactly(tuple(null, null));
    }

    @Test
    void voiceSmsCardShowsTemplateQuotaThenMirroredRemaining() {
        long card = h.acceptCard("O-2", c.skuBNew, "m1");
        h.drain();

        CardView before = queries.getForMember(card, "m1").orElseThrow();
        assertThat(before).extracting(CardView::capabilityText, CardView::voiceRemainingText, CardView::smsRemainingText)
                .containsExactly("流量 · 语音 · 短信", "100 分钟", "50 条");

        long plan = h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE card_asset_id = ?", Long.class, card);
        String planRef = h.jdbc.queryForObject("SELECT upstream_plan_ref FROM lr_card_plan WHERE id = ?", String.class, plan);
        h.bean(com.liroam.fulfillment.poll.UsageMirror.class).apply(card, new UsageSnapshot(null, UsageSnapshot.CardStatus.ACTIVE,
                java.util.List.of(new PlanUsage(planRef, 5L << 30, 1L << 30, 4L << 30, 6000L, 3270L, 2730L, 50, 3, 47, null, null, null, null)), "{}"),
                h.clock.instant());

        CardView after = queries.getForMember(card, "m1").orElseThrow();
        assertThat(after).extracting(CardView::remainingText, CardView::voiceRemainingText, CardView::smsRemainingText)
                .containsExactly("4 GB", "45 分钟", "47 条");
        assertThat(after.plans()).extracting(CardView.PlanView::voiceRemainingText, CardView.PlanView::smsRemainingText)
                .containsExactly(tuple("45 分钟", "47 条"));
        assertThat(after.plans()).extracting(CardView.PlanView::voiceUsageText, CardView.PlanView::smsUsageText)
                .containsExactly(tuple("已用 54 / 共 100 分钟", "已用 3 / 共 50 条"));
        assertThat(h.jdbc.queryForMap("SELECT voice_quota_seconds, voice_used_seconds, sms_quota, sms_used FROM lr_card_plan WHERE id = ?", plan))
                .containsEntry("voice_quota_seconds", 6000L).containsEntry("voice_used_seconds", 3270L)
                .containsEntry("sms_quota", 50).containsEntry("sms_used", 3);
    }

    @Test
    void voiceSmsUsageBeforeAnyMirrorComesFromTheTemplate() {
        long card = h.acceptCard("O-4", c.skuBNew, "m1");
        h.drain();

        CardView v = queries.getForMember(card, "m1").orElseThrow();

        assertThat(v.plans()).extracting(CardView.PlanView::voiceUsageText, CardView.PlanView::smsUsageText)
                .containsExactly(tuple("已用 0 / 共 100 分钟", "已用 0 / 共 50 条"));
    }

    @Test
    void dataOnlyPlanHasNoVoiceOrSmsUsage() {
        h.importPoolA(1, true);
        long card = h.acceptCard("O-5", c.skuANew, "m1");
        h.drain();

        assertThat(queries.getForMember(card, "m1").orElseThrow().plans())
                .extracting(CardView.PlanView::voiceUsageText, CardView.PlanView::smsUsageText).containsExactly(tuple(null, null));
    }

    /** 套餐「用完」= 每个有额度的维度都用尽；只用完流量、语音或短信还有余量时仍在用。 */
    @Test
    void planIsDepletedOnlyWhenEveryMeteredDimensionIsUsedUp() {
        long card = h.acceptCard("O-6", c.skuBNew, "m1");
        h.drain();
        long plan = h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE card_asset_id = ?", Long.class, card);
        String planRef = h.jdbc.queryForObject("SELECT upstream_plan_ref FROM lr_card_plan WHERE id = ?", String.class, plan);
        var mirror = h.bean(com.liroam.fulfillment.poll.UsageMirror.class);

        mirror.apply(card, new UsageSnapshot(null, UsageSnapshot.CardStatus.ACTIVE,
                java.util.List.of(new PlanUsage(planRef, 5L << 30, 5L << 30, 0L, 6000L, 600L, 5400L, 50, 50, 0, h.clock.instant(), null, null, null)),
                "{}"), h.clock.instant());
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE id = ?", String.class, plan)).isEqualTo("IN_USE");
        assertThat(queries.getForMember(card, "m1").orElseThrow()).extracting(CardView::remainingText, CardView::voiceRemainingText,
                CardView::smsRemainingText).containsExactly("0 B", "90 分钟", "0 条");

        mirror.apply(card, new UsageSnapshot(null, UsageSnapshot.CardStatus.ACTIVE,
                java.util.List.of(new PlanUsage(planRef, 5L << 30, 5L << 30, 0L, 6000L, 6000L, 0L, 50, 50, 0, h.clock.instant(), null, null, null)),
                "{}"), h.clock.instant());
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE id = ?", String.class, plan)).isEqualTo("DEPLETED");
    }

    @Test
    void dataOnlyPlanIsDepletedWhenDataRunsOut() {
        h.importPoolA(1, true);
        long card = h.acceptCard("O-7", c.skuANew, "m1");
        h.drain();
        long plan = h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE card_asset_id = ?", Long.class, card);
        String planRef = h.jdbc.queryForObject("SELECT upstream_plan_ref FROM lr_card_plan WHERE id = ?", String.class, plan);

        h.bean(com.liroam.fulfillment.poll.UsageMirror.class).apply(card, new UsageSnapshot(null, UsageSnapshot.CardStatus.ACTIVE,
                java.util.List.of(new PlanUsage(planRef, 3L << 30, 3L << 30, 0L, null, null, null, null, null, null, h.clock.instant(), null, null, null)),
                "{}"), h.clock.instant());

        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE id = ?", String.class, plan)).isEqualTo("DEPLETED");
    }

    /** 不限流量的语音卡：语音、短信用尽也不算用完（流量仍可用）。 */
    @Test
    void unlimitedDataPlanIsNotDepletedByVoiceAndSms() {
        long template = h.jdbc.queryForObject("SELECT plan_template_id FROM lr_sku_binding WHERE id = ?", Long.class, c.skuBNew);
        Long quota = h.jdbc.queryForObject("SELECT data_quota_bytes FROM lr_plan_template WHERE id = ?", Long.class, template);
        h.jdbc.update("UPDATE lr_plan_template SET data_quota_bytes = NULL WHERE id = ?", template);
        try {
            assertUnlimitedDataPlanStaysInUse();
        } finally {
            h.jdbc.update("UPDATE lr_plan_template SET data_quota_bytes = ? WHERE id = ?", quota, template);
        }
    }

    private void assertUnlimitedDataPlanStaysInUse() {
        long card = h.acceptCard("O-8", c.skuBNew, "m1");
        h.drain();
        long plan = h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE card_asset_id = ?", Long.class, card);
        String planRef = h.jdbc.queryForObject("SELECT upstream_plan_ref FROM lr_card_plan WHERE id = ?", String.class, plan);

        h.bean(com.liroam.fulfillment.poll.UsageMirror.class).apply(card, new UsageSnapshot(null, UsageSnapshot.CardStatus.ACTIVE,
                java.util.List.of(new PlanUsage(planRef, null, 7L << 30, null, 6000L, 6000L, 0L, 50, 50, 0, h.clock.instant(), null, null, null)),
                "{}"), h.clock.instant());

        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE id = ?", String.class, plan)).isEqualTo("IN_USE");
    }

    @Test
    void numberedSkuIsNotSellableUntilTheGlobalSwitchIsOn() {
        h.jdbc.update("UPDATE lr_sku_binding SET numbered_gate = 1 WHERE id = ?", c.skuBNew);

        assertThatThrownBy(() -> h.acceptShop("O-3", "O-3-1", c.skuBNew, 1, "m1")).isInstanceOf(FulfillmentRejected.class)
                .extracting(e -> ((FulfillmentRejected) e).reason()).isEqualTo(Reason.NUMBERED_SKU_DISABLED);
        assertThat(Reason.NUMBERED_SKU_DISABLED.userMessage()).isEqualTo("该商品暂不可售");

        h.bean(Settings.class).set(CatalogSettings.NUMBERED_SKU_ENABLED, SettingScope.GLOBAL, true, "op1");
        assertThat(h.acceptShop("O-3", "O-3-1", c.skuBNew, 1, "m1")).isPositive();
    }
}
