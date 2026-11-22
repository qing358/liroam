package com.liroam.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;

import com.liroam.fulfillment.card.CardKyc;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardQueries.CredentialView;
import com.liroam.fulfillment.card.CardView;
import com.liroam.fulfillment.credential.CredentialEntry;
import com.liroam.fulfillment.credential.Shipment;
import com.liroam.fulfillment.testing.TestCatalog;

/**
 * P4 全卡型主路径（delivery-master-plan P4）：纯流量、语音短信、实名、实体卡各走通一单——真 mock 上游出卡、唯一履约入口、
 * 「我的卡」同一投影、整单回写壳。实名卡在买家自报前不交码不回写；实体卡录物流即交付、买家看到运单而不是激活码。
 */
@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class CardTypesSmokeTest {

    private static final String MEMBER = "m-types";

    private SmokeHarness h;
    private TestCatalog c;
    private CardQueries queries;

    @BeforeAll
    void start() {
        h = new SmokeHarness("smoke_card_types");
        c = h.catalog;
        queries = h.bean(CardQueries.class);
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private long onlyCardOf(String orderSn) {
        return h.jdbc.queryForObject("SELECT c.id FROM lr_card_asset c JOIN lr_fulfillment_line l ON l.id = c.fulfillment_line_id "
                + "WHERE l.source_ref = ?", Long.class, orderSn);
    }

    private String status(long card) {
        return h.jdbc.queryForObject("SELECT status FROM lr_card_asset WHERE id = ?", String.class, card);
    }

    private CardView view(long card) {
        return queries.getForMember(card, MEMBER).orElseThrow();
    }

    @Test
    @Order(1)
    void dataCard() {
        h.importPoolA(List.of("8988800000000002001"));
        h.acceptShop("LO-T-DATA", "LO-T-DATA-1", c.skuANew, 1, MEMBER);
        h.drain();
        long card = onlyCardOf("LO-T-DATA");
        h.bean(CredentialEntry.class).enter(card, "LPA:1$types.example$DATA", "op-types");
        h.drain();

        assertThat(status(card)).isEqualTo("DELIVERED");
        assertThat(view(card)).extracting(CardView::capabilityText, CardView::remainingText, CardView::voiceRemainingText, CardView::smsRemainingText)
                .containsExactly("流量", "3 GB", null, null);
        assertThat(h.shell.calls()).containsExactly("LO-T-DATA");
    }

    @Test
    @Order(2)
    void voiceAndSmsCard() {
        h.acceptShop("LO-T-VOICE", "LO-T-VOICE-1", c.skuBNew, 1, MEMBER);
        h.drain();
        long card = onlyCardOf("LO-T-VOICE");

        assertThat(status(card)).as("B 开卡同步带回激活码").isEqualTo("DELIVERED");
        assertThat(view(card)).extracting(CardView::capabilityText, CardView::voiceRemainingText, CardView::smsRemainingText)
                .containsExactly("流量 · 语音 · 短信", "100 分钟", "50 条");
        assertThat(queries.credentialForMember(card, MEMBER)).hasValueSatisfying(v -> assertThat(v.payload()).isNotBlank());
        assertThat(h.shell.calls()).endsWith("LO-T-VOICE");
    }

    @Test
    @Order(3)
    void realNameCardWaitsForDeclarationThenDelivers() {
        h.jdbc.update("UPDATE lr_sku_binding SET kyc_gate_mode = 'BLOCK_DELIVERY' WHERE id = ?", c.skuBNew);
        h.acceptShop("LO-T-KYC", "LO-T-KYC-1", c.skuBNew, 1, MEMBER);
        h.drain();
        long card = onlyCardOf("LO-T-KYC");

        assertThat(status(card)).isEqualTo("PROVISIONED");
        assertThat(view(card)).extracting(CardView::statusText, CardView::kycBlocking, CardView::canDeclareKyc).containsExactly("待实名", true, true);
        assertThat(queries.credentialForMember(card, MEMBER)).as("实名前不出码").isEmpty();
        assertThat(h.shell.calls()).doesNotContain("LO-T-KYC");

        h.bean(CardKyc.class).declare(card, MEMBER);
        h.drain();

        assertThat(status(card)).isEqualTo("DELIVERED");
        assertThat(view(card)).extracting(CardView::kycText, CardView::kycBlocking).containsExactly("已提交实名", false);
        assertThat(queries.credentialForMember(card, MEMBER)).isPresent();
        assertThat(h.shell.calls()).containsOnlyOnce("LO-T-KYC");
    }

    @Test
    @Order(4)
    void physicalCardDeliversByShipment() {
        h.acceptShop("LO-T-SIM", "LO-T-SIM-1", c.skuBPhysical, 1, MEMBER);
        h.drain();
        long card = onlyCardOf("LO-T-SIM");
        assertThat(status(card)).as("实体卡不取激活码，等运营寄出").isEqualTo("PROVISIONED");
        assertThat(h.count("lr_supplier_command WHERE card_asset_id = ? AND type = 'FETCH_CREDENTIAL'", card)).isZero();

        h.bean(CredentialEntry.class).enterShipment(card, new Shipment("LOG-SF", "顺丰速运", "SF0000000001"), "op-types");
        h.drain();

        assertThat(status(card)).isEqualTo("DELIVERED");
        assertThat(queries.credentialForMember(card, MEMBER).orElseThrow())
                .extracting(CredentialView::kind, CredentialView::payload, CredentialView::logisticsCompany, CredentialView::logisticsNo)
                .containsExactly("SHIPMENT", null, "顺丰速运", "SF0000000001");
        assertThat(h.shell.calls()).containsOnlyOnce("LO-T-SIM");
        assertThat(queries.listForMember(MEMBER)).as("四种卡在「我的卡」同一列表").hasSize(4);
    }
}
