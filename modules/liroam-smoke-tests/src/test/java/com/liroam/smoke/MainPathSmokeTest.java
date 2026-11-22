package com.liroam.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;

import com.liroam.common.web.PageResult;
import com.liroam.fulfillment.CredentialDelivered;
import com.liroam.fulfillment.FulfillmentAck;
import com.liroam.fulfillment.FulfillmentRejected;
import com.liroam.fulfillment.FulfillmentRejected.Reason;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.card.AdminCardView;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardQueries.CardFilter;
import com.liroam.fulfillment.card.CardQueries.TopUpOption;
import com.liroam.fulfillment.card.CardView;
import com.liroam.fulfillment.card.CardViewProjector;
import com.liroam.fulfillment.credential.CredentialEntry;
import com.liroam.fulfillment.credential.CredentialImports;
import com.liroam.fulfillment.credential.CredentialImports.ImportReport;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.testing.RecordingCredentialEvents;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;

/**
 * P2 主路径冒烟（domain-design §15 V3–V7）：同一买家一单买形状 A ×2 + 形状 B ×1，经唯一履约入口收单，
 * worker 驱动真实 mock 上游出卡、两种交码、整单回写壳一次、「我的卡」同一投影、加包只动选中的卡。
 * 各步依次承接上一步的台账（与验收表的前置关系一致），所以按顺序跑、共用一个测试库。
 */
@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class MainPathSmokeTest {

    private static final String MEMBER = "m-smoke";
    private static final String ORDER = "LO-SMOKE-1";
    private static final String LINE_A = ORDER + "-A";
    private static final String LINE_B = ORDER + "-B";
    /** 尾号避开 mock 默认故障规则（9991 限流、9992 写后超时）。 */
    private static final List<String> POOL_A = List.of("8988800000000001001", "8988800000000001002");

    private SmokeHarness h;
    private TestCatalog c;
    private long a1;
    private long a2;
    private long b;
    private List<CardView> myCards;

    @BeforeAll
    void start() {
        h = new SmokeHarness("smoke_main_path");
        c = h.catalog;
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private String status(long card) {
        return h.jdbc.queryForObject("SELECT status FROM lr_card_asset WHERE id = ?", String.class, card);
    }

    private CardView view(List<CardView> views, long card) {
        return views.stream().filter(v -> v.id() == card).findFirst().orElseThrow();
    }

    @Test
    @Order(3)
    void v3_mixedCartBecomesTwoLinesAndThreeCardsWithOwnSupplierAndRegion() {
        h.importPoolA(POOL_A);

        FulfillmentAck lineA = h.acceptShop(ORDER, LINE_A, c.skuANew, 2, MEMBER);
        FulfillmentAck lineB = h.acceptShop(ORDER, LINE_B, c.skuBNew, 1, MEMBER);
        h.drain();

        assertThat(List.of(lineA.created(), lineB.created())).containsOnly(true);
        assertThat(h.jdbc.queryForList("SELECT source_line_ref, qty FROM lr_fulfillment_line ORDER BY source_line_ref"))
                .extracting(r -> r.get("source_line_ref"), r -> ((Number) r.get("qty")).intValue())
                .containsExactly(tuple(LINE_A, 2), tuple(LINE_B, 1));
        List<Map<String, Object>> cards = h.jdbc.queryForList("""
                SELECT c.id, s.code AS supplier, r.code AS region, c.owner_member_id, c.status, c.upstream_ref
                  FROM lr_card_asset c JOIN lr_fulfillment_line l ON l.id = c.fulfillment_line_id
                  JOIN lr_supplier s ON s.id = c.supplier_id JOIN lr_usage_region r ON r.id = c.region_id
                 ORDER BY l.source_line_ref, c.unit_seq
                """);
        assertThat(cards).extracting(r -> r.get("supplier"), r -> r.get("region"), r -> r.get("owner_member_id"))
                .containsExactly(tuple("SUP_A", "JP", MEMBER), tuple("SUP_A", "JP", MEMBER), tuple("SUP_B", "US", MEMBER));
        assertThat(cards).extracting(r -> r.get("status")).as("A 池卡已在上游开通、等交码；B 开卡同步带回激活码")
                .containsExactly("PROVISIONED", "PROVISIONED", "DELIVERED");
        assertThat(cards).extracting(r -> ((String) r.get("upstream_ref")).substring(0, 2)).as("真 mock 上游的卡引用前缀")
                .containsExactly("MA", "MA", "MB");
        a1 = ((Number) cards.get(0).get("id")).longValue();
        a2 = ((Number) cards.get(1).get("id")).longValue();
        b = ((Number) cards.get(2).get("id")).longValue();
        assertThat(h.shell.calls()).as("A 未交码，整单未完成，不回写壳").isEmpty();
    }

    @Test
    @Order(4)
    void v4_redeliveredPaymentAddsNoLinesOrCards() {
        int commands = h.count("lr_supplier_command");

        FulfillmentAck againA = h.acceptShop(ORDER, LINE_A, c.skuANew, 2, MEMBER);
        FulfillmentAck againB = h.acceptShop(ORDER, LINE_B, c.skuBNew, 1, MEMBER);
        h.drain();

        assertThat(List.of(againA.created(), againB.created())).containsOnly(false);
        assertThat(h.count("lr_fulfillment_line")).isEqualTo(2);
        assertThat(h.count("lr_card_asset")).isEqualTo(3);
        assertThat(h.count("lr_supplier_command")).isEqualTo(commands);
    }

    @Test
    @Order(5)
    void v5_importAndManualForShapeA_apiForShapeB_allDelivered_shellNotifiedOnce() {
        String a1Iccid = POOL_A.stream().filter(i -> i.endsWith(h.jdbc.queryForObject(
                "SELECT iccid_last4 FROM lr_card_asset WHERE id = ?", String.class, a1))).findFirst().orElseThrow();

        ImportReport report = h.bean(CredentialImports.class).importCsv(new StringReader("iccid,lpa\n" + a1Iccid + ",LPA:1$smoke.example$A1\n"),
                "op-smoke");
        h.drain();

        assertThat(report.delivered()).isEqualTo(1);
        assertThat(report.unmatched()).isEmpty();
        assertThat(status(a1)).isEqualTo("DELIVERED");
        assertThat(status(a2)).isEqualTo("PROVISIONED");
        assertThat(h.shell.calls()).as("A2 尚未交码").isEmpty();

        h.bean(CredentialEntry.class).enter(a2, "LPA:1$smoke.example$A2", "op-smoke");
        h.drain();
        h.acceptShop(ORDER, LINE_A, c.skuANew, 2, MEMBER);
        h.drain();

        assertThat(List.of(a1, a2, b)).allSatisfy(card -> assertThat(status(card)).isEqualTo("DELIVERED"));
        assertThat(h.jdbc.queryForList("SELECT status FROM lr_fulfillment_line ORDER BY source_line_ref", String.class))
                .containsExactly("COMPLETED", "COMPLETED");
        assertThat(h.bean(RecordingCredentialEvents.class).events()).containsExactlyInAnyOrder(
                new CredentialDelivered(b, CredentialChannel.API), new CredentialDelivered(a1, CredentialChannel.IMPORT),
                new CredentialDelivered(a2, CredentialChannel.MANUAL));
        assertThat(h.shell.calls()).as("H5 壳发货回写：整单一次").containsExactly(ORDER);
    }

    @Test
    @Order(6)
    void v6_myCardsShowsThreeCardsFromTheSameProjectionAsTheAdminLedger() {
        CardQueries queries = h.bean(CardQueries.class);

        myCards = queries.listForMember(MEMBER);
        PageResult<AdminCardView> ledger = queries.page(new CardFilter(null, null, null, 1, 10));

        assertThat(myCards).extracting(CardView::id).containsExactlyInAnyOrder(a1, a2, b);
        assertThat(myCards).extracting(CardView::id, CardView::supplierCode, CardView::productName, CardView::usageRegionName)
                .containsExactlyInAnyOrder(tuple(a1, "SUP_A", "亚洲漫游卡", "日本"), tuple(a2, "SUP_A", "亚洲漫游卡", "日本"),
                        tuple(b, "SUP_B", "美国畅游卡", "美国"));
        assertThat(myCards).allSatisfy(v -> {
            assertThat(v.statusText()).isEqualTo(CardViewProjector.statusText(CardAssetStatus.DELIVERED));
            assertThat(v.remainingText()).isNotBlank();
            assertThat(v.expiresText()).isNotBlank();
            assertThat(v.updatedText()).isNotBlank();
            assertThat(v.canTopUp()).isTrue();
        });
        assertThat(ledger.records()).extracting(AdminCardView::card).as("后台卡台账与「我的卡」是同一份投影")
                .containsExactlyInAnyOrderElementsOf(myCards);
    }

    @Test
    @Order(7)
    void v7_topUpChangesOnlyTheChosenCard_crossSupplierTopUpRejectedWithOneMessage() {
        CardView a1Before = view(myCards, a1);

        TopUpIntents.Opened intent = h.bean(TopUpIntents.class).open(MEMBER, a1, "SKU-A-TOP");
        h.acceptTopUp("LO-SMOKE-2", "LO-SMOKE-2-1", c.skuATopUp, MEMBER, a1);
        h.drain();
        List<CardView> after = h.bean(CardQueries.class).listForMember(MEMBER);

        assertThat(intent.skuId()).isEqualTo("SKU-A-TOP");
        CardView a1After = view(after, a1);
        assertThat(a1After.plans()).hasSize(a1Before.plans().size() + 1);
        assertThat(a1After.remainingText()).isNotEqualTo(a1Before.remainingText());
        assertThat(view(after, a2)).as("同单另一张 A 卡不受影响").isEqualTo(view(myCards, a2));
        assertThat(view(after, b)).isEqualTo(view(myCards, b));
        assertThat(h.count("lr_card_asset")).as("加包不建新卡").isEqualTo(3);

        assertThat(h.bean(CardQueries.class).topUpOptions(a1, MEMBER)).extracting(TopUpOption::skuId).as("加包选卡页只列同供应商的包")
                .containsExactly("SKU-A-TOP");
        assertThatThrownBy(() -> h.bean(TopUpIntents.class).open(MEMBER, a1, "SKU-B-TOP")).as("前端选卡页")
                .isInstanceOfSatisfying(FulfillmentRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOPUP_CARD_OTHER_SUPPLIER));
        assertThatThrownBy(() -> h.acceptTopUp("LO-SMOKE-3", "LO-SMOKE-3-1", c.skuBTopUp, MEMBER, a1)).as("后端履约入口")
                .isInstanceOfSatisfying(FulfillmentRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOPUP_CARD_OTHER_SUPPLIER));
        assertThat(h.count("lr_fulfillment_line WHERE source_ref = 'LO-SMOKE-3'")).isZero();
        assertThat(h.shell.calls()).as("首单仍只回写一次").containsOnlyOnce(ORDER);
    }
}
