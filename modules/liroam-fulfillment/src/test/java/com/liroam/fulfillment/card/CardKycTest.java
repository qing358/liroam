package com.liroam.fulfillment.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.state.KycStatus;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;

/**
 * 实名闸（domain-design §7.3）：BLOCK_DELIVERY 下实名未通过不交付、不出码；买家自报或后台核验通过后自动交付并回写壳；
 * PROMPT 只提示不挡；NONE 不要求。买家与后台看到的实名文案出自同一投影。
 */
class CardKycTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static CardKyc kyc;
    private static CardQueries queries;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_card_kyc");
        c = h.catalog;
        kyc = h.bean(CardKyc.class);
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

    private long blockedCard(String orderSn) {
        h.jdbc.update("UPDATE lr_sku_binding SET kyc_gate_mode = 'BLOCK_DELIVERY' WHERE id = ?", c.skuBNew);
        long card = h.acceptCard(orderSn, c.skuBNew, "m1");
        h.drain();
        return card;
    }

    private CardView view(long card) {
        return queries.getForMember(card, "m1").orElseThrow();
    }

    @Test
    void blockedCardWaitsForKycWithCredentialStoredButHidden() {
        long card = blockedCard("O-1");

        assertThat(h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");
        CardView v = view(card);
        assertThat(v).extracting(CardView::statusText, CardView::kycStatus, CardView::kycText, CardView::kycBlocking, CardView::canDeclareKyc)
                .containsExactly("待实名", "PENDING", "待实名", true, true);
        assertThat(queries.credentialForMember(card, "m1")).as("实名前不出码").isEmpty();
        assertThat(h.shell.calls()).isEmpty();
    }

    @Test
    void buyerDeclarationReleasesDeliveryAndCompletesTheOrder() {
        long card = blockedCard("O-2");

        kyc.declare(card, "m1");
        h.drain();

        assertThat(h.jdbc.queryForObject("SELECT kyc_status FROM lr_card_asset WHERE id = ?", String.class, card)).isEqualTo("USER_DECLARED");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        assertThat(queries.credentialForMember(card, "m1")).isPresent();
        assertThat(view(card)).extracting(CardView::statusText, CardView::kycText, CardView::kycBlocking, CardView::canDeclareKyc)
                .containsExactly("待激活", "已提交实名", false, false);
        assertThat(h.shell.calls()).containsExactly("O-2");
        assertThat(h.count("lr_audit_log WHERE action = 'KYC_DECLARE' AND target_id = ?", String.valueOf(card))).isEqualTo(1);
    }

    @Test
    void operatorVerificationAlsoReleasesDelivery() {
        long card = blockedCard("O-3");

        kyc.review(card, KycStatus.VERIFIED, "证件核验通过", "op1");
        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        assertThat(view(card).kycText()).isEqualTo("实名已核验");
        assertThat(h.count("lr_audit_log WHERE action = 'KYC_REVIEW' AND actor = 'op1'")).isEqualTo(1);
        assertThatThrownBy(() -> kyc.declare(card, "m1")).isInstanceOf(UserFacingException.class).hasMessage("实名已核验，无需再次提交");
    }

    @Test
    void rejectionKeepsBlockingAndBuyerCanDeclareAgain() {
        long card = blockedCard("O-4");

        kyc.review(card, KycStatus.REJECTED, "证件信息不符", "op1");
        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");
        assertThat(view(card)).extracting(CardView::kycText, CardView::kycBlocking, CardView::canDeclareKyc).containsExactly("实名未通过", true, true);
        kyc.declare(card, "m1");
        h.drain();
        assertThat(view(card).kycStatus()).isEqualTo("USER_DECLARED");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
    }

    @Test
    void promptModeDeliversRightAwayAndStillOffersDeclaration() {
        h.jdbc.update("UPDATE lr_sku_binding SET kyc_gate_mode = 'PROMPT' WHERE id = ?", c.skuBNew);
        long card = h.acceptCard("O-5", c.skuBNew, "m1");
        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        assertThat(view(card)).extracting(CardView::kycText, CardView::kycBlocking, CardView::canDeclareKyc).containsExactly("待实名", false, true);
        kyc.declare(card, "m1");
        assertThat(view(card).kycText()).isEqualTo("已提交实名");
    }

    @Test
    void cardsWithoutKycRequirementRejectDeclarationAndOthersCardsAreInvisible() {
        long card = h.acceptCard("O-6", c.skuBNew, "m1");
        h.drain();

        assertThat(view(card)).extracting(CardView::kycText, CardView::canDeclareKyc).containsExactly("无需实名", false);
        assertThatThrownBy(() -> kyc.declare(card, "m1")).isInstanceOf(UserFacingException.class).hasMessage("这张卡无需实名");
        assertThatThrownBy(() -> kyc.declare(card, "m2")).isInstanceOf(UserFacingException.class).hasMessage("找不到这张卡");
        assertThatThrownBy(() -> kyc.review(card, KycStatus.VERIFIED, null, "op1")).isInstanceOf(UserFacingException.class)
                .hasMessage("这张卡无需实名");
        assertThatThrownBy(() -> kyc.review(card, KycStatus.PENDING, null, "op1")).isInstanceOf(UserFacingException.class)
                .hasMessage("核验结果只能是通过或驳回");
    }
}
