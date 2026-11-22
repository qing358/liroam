package com.liroam.fulfillment.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.CardQueries.CardFilter;
import com.liroam.fulfillment.card.CardQueries.CredentialView;
import com.liroam.fulfillment.card.CardQueries.TopUpOption;
import com.liroam.fulfillment.poll.PollScheduler;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.supplier.spi.CardRef;

/** 「我的卡」同一投影（V6）、查看激活码留痕与退款冻结不出码、加包选项、后台分页筛选与详情。 */
class CardQueriesTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static CardQueries queries;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_card_queries");
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

    /** m1：A 两张（同一行 qty=2，池里带码）+ B 一张（已用过一阵）；m2：B 一张。返回 [a1, a2, b, other]。 */
    private long[] seedCards() {
        List<String> iccids = h.importPoolA(2, true);
        long lineA = h.acceptShop("O-A", "O-A-1", c.skuANew, 2, "m1");
        long b = h.acceptCard("O-B", c.skuBNew, "m1");
        long other = h.acceptCard("O-X", c.skuBNew, "m2");
        h.drain();
        List<Long> a = h.jdbc.queryForList("SELECT id FROM lr_card_asset WHERE fulfillment_line_id = ? ORDER BY unit_seq", Long.class, lineA);
        assertThat(iccids).hasSize(2);
        String ref = h.jdbc.queryForObject("SELECT upstream_ref FROM lr_card_asset WHERE id = ?", String.class, b);
        h.upstreamB.seedUsage(new CardRef(b, null, ref));
        h.clock.advance(Duration.ofHours(6));
        h.bean(PollScheduler.class).runOnce();
        h.drain();
        return new long[] {a.get(0), a.get(1), b, other};
    }

    @Test
    void v6MyCardsShowsEachCardIndependentlyFromOneProjection() {
        long[] ids = seedCards();

        List<CardView> mine = queries.listForMember("m1");

        assertThat(mine).extracting(CardView::id).containsExactly(ids[2], ids[1], ids[0]);
        CardView b = mine.get(0);
        assertThat(b).extracting(CardView::productName, CardView::usageRegionName, CardView::supplierCode, CardView::status, CardView::statusText,
                CardView::remainingText, CardView::expiresText, CardView::updatedText, CardView::canTopUp, CardView::canRefund)
                .containsExactly("美国畅游卡", "美国", "SUP_B", "IN_USE", "使用中", "1536 B", "2026-12-31 23:59",
                        "2026-11-22 18:00", true, true);
        assertThat(b.plans()).extracting(CardView.PlanView::name, CardView.PlanView::statusText, CardView.PlanView::remainingText,
                CardView.PlanView::expiresText).containsExactly(tuple("美国 7 天 5GB", "使用中", "1536 B", "2026-12-31 23:59"));

        for (CardView a : mine.subList(1, 3)) {
            assertThat(a).extracting(CardView::productName, CardView::usageRegionName, CardView::status, CardView::statusText,
                    CardView::remainingText, CardView::expiresText, CardView::canTopUp, CardView::canRefund, CardView::refundHold)
                    .containsExactly("亚洲漫游卡", "日本", "DELIVERED", "待激活", "3 GB", "—", true, true, false);
            assertThat(a.plans()).extracting(CardView.PlanView::statusText, CardView.PlanView::expiresText)
                    .containsExactly(tuple("未激活", "激活后 5 天有效"));
            assertThat(a.iccidLast4()).hasSize(4);
        }
        assertThat(mine.get(1).cardNo()).isNotEqualTo(mine.get(2).cardNo());

        assertThat(queries.getForMember(ids[2], "m1")).contains(b);
        assertThat(queries.getForMember(ids[3], "m1")).as("别人的卡").isEmpty();
        assertThat(queries.listForMember("nobody")).isEmpty();
    }

    @Test
    void credentialIsShownOnlyToOwnerOfDeliveredCardAndEveryReadIsLogged() {
        long[] ids = seedCards();
        String iccid = h.jdbc.queryForObject("SELECT iccid_last4 FROM lr_card_asset WHERE id = ?", String.class, ids[0]);

        CredentialView view = queries.credentialForMember(ids[0], "m1").orElseThrow();
        queries.credentialForMember(ids[0], "m1");

        assertThat(view.kind()).isEqualTo("LPA");
        assertThat(view.channel()).isEqualTo("IMPORT");
        assertThat(view.payload()).startsWith("LPA:1$pool.example$").endsWith(iccid);
        assertThat(view.deliveredText()).isEqualTo("2026-11-22 12:00");
        assertThat(view.toString()).doesNotContain("pool.example");
        assertThat(h.jdbc.queryForList("SELECT CONCAT(operator_type, ':', operator_id, ':', target_type, ':', target_id, ':', field) "
                + "FROM lr_sensitive_access_log", String.class)).containsExactly("MEMBER:m1:CARD:" + ids[0] + ":CREDENTIAL",
                        "MEMBER:m1:CARD:" + ids[0] + ":CREDENTIAL");

        assertThatThrownBy(() -> queries.credentialForMember(ids[3], "m1")).isInstanceOf(UserFacingException.class).hasMessage("找不到这张卡");
        h.bean(CardActions.class).hold(ids[0], "op");
        assertThatThrownBy(() -> queries.credentialForMember(ids[0], "m1")).isInstanceOf(UserFacingException.class)
                .hasMessage("该卡退款处理中，暂不能查看激活码");
        assertThat(h.count("lr_sensitive_access_log")).as("被拒的不留痕也不出码").isEqualTo(2);
    }

    @Test
    void credentialIsEmptyBeforeDelivery() {
        long card = h.acceptCard("O-1", c.skuANew, "m1");

        assertThat(queries.credentialForMember(card, "m1")).isEmpty();
        assertThat(h.count("lr_sensitive_access_log")).isZero();
    }

    @Test
    void topUpOptionsListSameSupplierListedPricedSkusOnlyForTopUpableOwnCard() {
        long[] ids = seedCards();

        assertThat(queries.topUpOptions(ids[0], "m1")).containsExactly(new TopUpOption("SKU-A-TOP", "G-A-TOP", "日本加油包 1GB", 1990));
        assertThat(queries.topUpOptions(ids[2], "m1")).containsExactly(new TopUpOption("SKU-B-TOP", "G-B-TOP", "美国加油包 2GB", 2990));
        assertThat(queries.topUpOptions(ids[3], "m1")).as("别人的卡").isEmpty();

        h.jdbc.update("UPDATE lr_sku_price_version SET status = 'RETIRED' WHERE sku_binding_id = ?", c.skuBTopUp);
        assertThat(queries.topUpOptions(ids[2], "m1")).as("没有生效售价").isEmpty();
        h.jdbc.update("UPDATE lr_sku_binding SET listing_state = 'DELISTED' WHERE id = ?", c.skuATopUp);
        assertThat(queries.topUpOptions(ids[0], "m1")).as("已下架").isEmpty();
        h.jdbc.update("UPDATE lr_sku_binding SET listing_state = 'LISTED' WHERE id = ?", c.skuATopUp);

        h.bean(CardActions.class).hold(ids[1], "op");
        assertThat(queries.topUpOptions(ids[1], "m1")).as("退款冻结").isEmpty();
        assertThat(queries.getForMember(ids[1], "m1").orElseThrow()).extracting(CardView::canTopUp, CardView::canRefund, CardView::refundHold)
                .containsExactly(false, false, true);

        long undelivered = h.acceptCard("O-2", c.skuANew, "m1");
        assertThat(queries.topUpOptions(undelivered, "m1")).as("还没交付").isEmpty();
        assertThat(queries.getForMember(undelivered, "m1").orElseThrow()).extracting(CardView::statusText, CardView::canTopUp, CardView::canRefund)
                .containsExactly("开通中", false, true);

        h.bean(CardActions.class).finalizeRefund(ids[1], CardActions.RefundEnd.TERMINATED, CardActions.PoolDisposition.VOID, "op");
        assertThat(queries.getForMember(ids[1], "m1").orElseThrow()).as("已终止不能再退").extracting(CardView::canRefund, CardView::refundHold)
                .containsExactly(false, false);
    }

    @Test
    void adminPageFiltersByStatusSupplierAndKeywordAndDetailCarriesCommandHistory() {
        long[] ids = seedCards();
        String last4 = h.jdbc.queryForObject("SELECT iccid_last4 FROM lr_card_asset WHERE id = ?", String.class, ids[1]);

        PageResult<AdminCardView> all = queries.page(new CardFilter(null, null, null, 1, 3));
        assertThat(all.total()).isEqualTo(4);
        assertThat(all.records()).extracting(r -> r.card().id()).containsExactly(ids[3], ids[2], ids[1]);
        assertThat(all.records().get(0)).extracting(AdminCardView::ownerMemberId, AdminCardView::orderSn).containsExactly("m2", "O-X");
        assertThat(all.records().get(0).createdText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(queries.page(new CardFilter(null, null, null, 2, 3)).records()).extracting(r -> r.card().id()).containsExactly(ids[0]);

        assertThat(queries.page(new CardFilter("IN_USE", null, null, 1, 20)).records()).extracting(r -> r.card().id()).containsExactly(ids[2]);
        assertThat(queries.page(new CardFilter(null, "SUP_A", null, 1, 20)).total()).isEqualTo(2);
        assertThat(queries.page(new CardFilter(null, null, "O-X", 1, 20)).records()).extracting(r -> r.card().id()).containsExactly(ids[3]);
        assertThat(queries.page(new CardFilter(null, null, "m1", 1, 20)).total()).isEqualTo(3);
        assertThat(queries.page(new CardFilter(null, null, last4, 1, 20)).records()).extracting(r -> r.card().id()).contains(ids[1]);
        assertThat(queries.page(new CardFilter("TERMINATED", "SUP_B", " ", 1, 20)).total()).isZero();

        AdminCardView.Detail detail = queries.detail(ids[2]).orElseThrow();
        assertThat(detail.row().card().statusText()).isEqualTo("使用中");
        assertThat(detail.commands()).extracting(AdminCardView.CommandView::type, AdminCardView.CommandView::status)
                .containsExactly(tuple("QUERY", "SUCCEEDED"), tuple("FETCH_CREDENTIAL", "SUCCEEDED"), tuple("PROVISION", "SUCCEEDED"));
        assertThat(queries.detail(999_999)).isEmpty();
    }
}
