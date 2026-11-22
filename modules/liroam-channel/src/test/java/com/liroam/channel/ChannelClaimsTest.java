package com.liroam.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.channel.ChannelClaims.ClaimResult;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.testing.ChannelFixture;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardView;
import com.liroam.fulfillment.testing.MutableClock;

/** 买家认领渠道单：渠道允许看用量、手机号 = 当前账号绑定手机号 = 下单收货手机号；卡属主从空写一次，之后进「我的卡」。 */
class ChannelClaimsTest {

    private static final String PHONE = "13800138000";

    private static ChannelFixture f;
    private static ChannelClaims claims;

    @BeforeAll
    static void start() {
        f = ChannelFixture.start("channel_claims");
        claims = f.bean(ChannelClaims.class);
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
        f.phones.bind("m1", PHONE);
    }

    private void deliveredOrder(String id) {
        f.h.importPoolA(2, true);
        f.adapter.addOrder(id, MutableClock.START, PHONE, new ExternalOrderLine("1", "EXT-A", 2, 9800));
        f.bean(ChannelOrders.class).pullNow(f.channelId, "op");
        f.h.drain();
    }

    @Test
    void claimBindsEveryCardOfTheOrderToTheMemberAndTheyShowInMyCards() {
        deliveredOrder("O-1");
        assertThat(f.bean(CardQueries.class).listForMember("m1")).isEmpty();

        ClaimResult result = claims.claim("m1", " O-1 ", "+86 138-0013-8000");

        assertThat(result.cardCount()).isEqualTo(2);
        List<CardView> mine = f.bean(CardQueries.class).listForMember("m1");
        assertThat(mine).extracting(CardView::id).containsExactlyInAnyOrderElementsOf(f.cardsOf("O-1"));
        assertThat(mine).allSatisfy(card -> assertThat(card.status()).isEqualTo("DELIVERED"));
        assertThat(f.h.jdbc.queryForObject("SELECT CONCAT(claimed_member_id, '|', claimed_at IS NOT NULL) FROM lr_channel_order", String.class))
                .isEqualTo("m1|1");
        assertThat(f.h.count("lr_audit_log WHERE action = 'CHANNEL_ORDER_CLAIM'")).isEqualTo(1);
    }

    @Test
    void phoneMustEqualTheMembersBoundPhone() {
        deliveredOrder("O-2");
        f.phones.bind("m2", "13900000000");

        assertThatThrownBy(() -> claims.claim("m2", "O-2", PHONE)).isInstanceOf(UserFacingException.class)
                .hasMessage("手机号与当前账号绑定的手机号不一致");
        assertThatThrownBy(() -> claims.claim("m3", "O-2", PHONE)).isInstanceOf(UserFacingException.class)
                .hasMessage("请先给当前账号绑定手机号");
        assertThat(f.h.count("lr_card_asset WHERE owner_member_id IS NOT NULL")).isZero();
    }

    @Test
    void orderNumberAndPhoneMustMatchAndTheChannelMustAllowBuyerViewing() {
        deliveredOrder("O-3");
        f.phones.bind("m2", "13900000000");

        assertThatThrownBy(() -> claims.claim("m1", "O-NOPE", PHONE)).isInstanceOf(UserFacingException.class)
                .hasMessage("找不到该渠道订单，请核对订单号与收货手机号");
        assertThatThrownBy(() -> claims.claim("m2", "O-3", "13900000000")).as("手机号不是该单收货手机号").isInstanceOf(UserFacingException.class)
                .hasMessage("找不到该渠道订单，请核对订单号与收货手机号");
        f.h.jdbc.update("UPDATE lr_channel SET buyer_can_view_usage = 0");
        assertThatThrownBy(() -> claims.claim("m1", "O-3", PHONE)).isInstanceOf(UserFacingException.class)
                .hasMessage("找不到该渠道订单，请核对订单号与收货手机号");
        assertThat(f.h.count("lr_card_asset WHERE owner_member_id IS NOT NULL")).isZero();
    }

    @Test
    void anOrderCanBeClaimedOnlyOnce() {
        deliveredOrder("O-4");
        f.phones.bind("m2", PHONE);
        claims.claim("m1", "O-4", PHONE);

        assertThatThrownBy(() -> claims.claim("m2", "O-4", PHONE)).isInstanceOf(UserFacingException.class).hasMessage("该订单已被认领");
        assertThatThrownBy(() -> claims.claim("m1", "O-4", PHONE)).isInstanceOf(UserFacingException.class).hasMessage("该订单已被认领");
        assertThat(f.h.jdbc.queryForList("SELECT DISTINCT owner_member_id FROM lr_card_asset", String.class)).containsExactly("m1");
    }

    @Test
    void orderWhoseCardsAreNotAllCreatedYetCannotBeClaimed() {
        f.adapter.addOrder("O-5", MutableClock.START, PHONE, new ExternalOrderLine("1", "EXT-A", 2, 9800));
        f.bean(ChannelOrders.class).pullNow(f.channelId, "op");

        assertThatThrownBy(() -> claims.claim("m1", "O-5", PHONE)).isInstanceOf(UserFacingException.class).hasMessage("订单还在处理中，请稍后再认领");
        assertThat(f.h.jdbc.queryForObject("SELECT claimed_member_id FROM lr_channel_order", String.class)).isNull();
    }
}
