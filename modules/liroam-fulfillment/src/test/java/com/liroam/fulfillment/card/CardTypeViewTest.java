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
                java.util.List.of(new PlanUsage(planRef, 5L << 30, 1L << 30, 4L << 30, 2730L, 47, null, null, null, null)), "{}"), h.clock.instant());

        CardView after = queries.getForMember(card, "m1").orElseThrow();
        assertThat(after).extracting(CardView::remainingText, CardView::voiceRemainingText, CardView::smsRemainingText)
                .containsExactly("4 GB", "45 分钟", "47 条");
        assertThat(after.plans()).extracting(CardView.PlanView::voiceRemainingText, CardView.PlanView::smsRemainingText)
                .containsExactly(tuple("45 分钟", "47 条"));
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
