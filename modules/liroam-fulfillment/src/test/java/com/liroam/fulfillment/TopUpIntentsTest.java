package com.liroam.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.fulfillment.FulfillmentRejected.Reason;
import com.liroam.fulfillment.TopUpIntents.Opened;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardView;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;

/** 加包选卡（V7）：开意图与下单履约同一组检查、同一份提示；下单绑定最新意图且重复绑定幂等；只给选中的卡加包。 */
class TopUpIntentsTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static TopUpIntents intents;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_topup_intents");
        c = h.catalog;
        intents = h.bean(TopUpIntents.class);
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    private long deliveredA(String order, String member) {
        h.importPoolA(1, true);
        long card = h.acceptCard(order, c.skuANew, member);
        h.drain();
        return card;
    }

    private static Reason openRejected(String member, long card, String sku) {
        return catchThrowableOfType(FulfillmentRejected.class, () -> intents.open(member, card, sku)).reason();
    }

    private static Reason acceptRejected(String member, long card, long skuBinding) {
        return catchThrowableOfType(FulfillmentRejected.class,
                () -> h.acceptTopUp("O-REJ", "O-REJ-1", skuBinding, member, card)).reason();
    }

    @Test
    void v7TopUpOnlyChangesChosenCardAndCrossSupplierIsRejectedWithSameMessageAsAccept() {
        long a1 = deliveredA("O-1", "m1");
        long a2 = deliveredA("O-2", "m1");

        Opened opened = intents.open("m1", a1, "SKU-A-TOP");
        assertThat(opened).isEqualTo(new Opened(opened.intentId(), "SKU-A-TOP", "G-A-TOP"));
        assertThat(intents.bind("SO-1", "m1", "SKU-A-TOP")).contains(a1);
        long line = h.acceptTopUp("SO-1", "SO-1-1", c.skuATopUp, "m1", intents.boundCard("SO-1", "SKU-A-TOP").orElseThrow());
        h.drain();

        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("COMPLETED");
        CardQueries queries = h.bean(CardQueries.class);
        CardView one = queries.getForMember(a1, "m1").orElseThrow();
        CardView two = queries.getForMember(a2, "m1").orElseThrow();
        assertThat(one.plans()).hasSize(2);
        assertThat(one.remainingText()).isEqualTo("4 GB");
        assertThat(two.plans()).hasSize(1);
        assertThat(two.remainingText()).isEqualTo("3 GB");

        Reason viaPage = openRejected("m1", a1, "SKU-B-TOP");
        Reason viaAccept = acceptRejected("m1", a1, c.skuBTopUp);
        assertThat(viaPage).isEqualTo(viaAccept).isEqualTo(Reason.TOPUP_CARD_OTHER_SUPPLIER);
        assertThat(viaPage.userMessage()).isEqualTo("这个加包与该卡不匹配，请选择同一卡型的加包");
        assertThat(h.count("lr_topup_intent")).as("被拒不留意图").isEqualTo(1);
    }

    @Test
    void openAppliesEveryTopUpRuleExactlyLikeAccept() {
        long card = deliveredA("O-3", "m1");
        long other = deliveredA("O-4", "m2");

        assertThat(openRejected("m1", other, "SKU-A-TOP")).isEqualTo(acceptRejected("m1", other, c.skuATopUp))
                .isEqualTo(Reason.TOPUP_CARD_NOT_OWNED);
        assertThat(openRejected("m1", 999_999, "SKU-A-TOP")).isEqualTo(acceptRejected("m1", 999_999, c.skuATopUp))
                .isEqualTo(Reason.TOPUP_CARD_NOT_FOUND);
        assertThat(openRejected("m1", card, "SKU-A")).isEqualTo(acceptRejected("m1", card, c.skuANew)).isEqualTo(Reason.SKU_KIND_MISMATCH);
        assertThat(openRejected("m1", card, "NOT-A-SKU")).isEqualTo(Reason.SKU_NOT_BOUND);

        h.bean(CardActions.class).hold(card, "op");
        assertThat(openRejected("m1", card, "SKU-A-TOP")).isEqualTo(acceptRejected("m1", card, c.skuATopUp))
                .isEqualTo(Reason.TOPUP_CARD_REFUND_HOLD);
        h.bean(CardActions.class).release(card, "op");

        h.jdbc.update("UPDATE lr_sku_binding SET listing_state = 'DELISTED' WHERE id = ?", c.skuATopUp);
        assertThat(openRejected("m1", card, "SKU-A-TOP")).isEqualTo(acceptRejected("m1", card, c.skuATopUp)).isEqualTo(Reason.SKU_NOT_LISTED);
        h.jdbc.update("UPDATE lr_sku_binding SET listing_state = 'LISTED' WHERE id = ?", c.skuATopUp);

        h.jdbc.update("UPDATE lr_supplier SET listing_enabled = 0 WHERE id = ?", c.supplierA);
        assertThat(openRejected("m1", card, "SKU-A-TOP")).isEqualTo(acceptRejected("m1", card, c.skuATopUp))
                .isEqualTo(Reason.SUPPLIER_NOT_LISTABLE);
        h.jdbc.update("UPDATE lr_supplier SET listing_enabled = 1 WHERE id = ?", c.supplierA);

        h.bean(CardActions.class).finalizeRefund(card, CardActions.RefundEnd.TERMINATED, CardActions.PoolDisposition.VOID, "op");
        assertThat(openRejected("m1", card, "SKU-A-TOP")).isEqualTo(acceptRejected("m1", card, c.skuATopUp))
                .isEqualTo(Reason.TOPUP_CARD_CLOSED);
        assertThat(h.count("lr_topup_intent")).isZero();
    }

    @Test
    void bindTakesLatestOpenIntentOfMemberAndSkuAndIsIdempotent() {
        long a1 = deliveredA("O-5", "m1");
        long a2 = deliveredA("O-6", "m1");
        intents.open("m1", a1, "SKU-A-TOP");
        intents.open("m1", a2, "SKU-A-TOP");

        assertThat(intents.bind("SO-2", "m1", "SKU-A-TOP")).as("最近一次选的卡").contains(a2);
        assertThat(intents.bind("SO-2", "m1", "SKU-A-TOP")).as("重复绑定幂等").contains(a2);
        assertThat(intents.boundCard("SO-2", "SKU-A-TOP")).contains(a2);
        assertThat(intents.bind("SO-3", "m1", "SKU-A-TOP")).as("剩下的那条").contains(a1);
        assertThat(intents.bind("SO-4", "m1", "SKU-A-TOP")).as("没有未绑定的意图").isEqualTo(Optional.empty());
        assertThat(intents.bind("SO-5", "m2", "SKU-A-TOP")).as("别人的意图不串").isEmpty();
        assertThat(h.count("lr_topup_intent WHERE status = 'BOUND'")).isEqualTo(2);
    }
}
