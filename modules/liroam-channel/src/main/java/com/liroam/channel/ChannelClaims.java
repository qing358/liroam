package com.liroam.channel;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.pricing.Json;

/**
 * 买家认领渠道单（domain-design §11）：渠道允许买家看用量时，凭渠道单号 + 收货手机号认领；手机号须等于当前账号绑定的手机号。
 * 认领把整单的卡属主从空写成当前会员（只写一次），之后这些卡出现在「我的卡」。
 */
public class ChannelClaims {

    private static final String NOT_FOUND = "找不到该渠道订单，请核对订单号与收货手机号";

    public record ClaimResult(String externalOrderId, int cardCount) {
    }

    private record Claimable(long id, String externalOrderId, String status, String claimedMemberId, int qty) {
    }

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final ObjectProvider<MemberPhones> phones;
    private final SensitiveCipher cipher;
    private final CardActions cardActions;
    private final AuditLog audit;
    private final Clock clock;

    public ChannelClaims(TransactionTemplate tx, JdbcClient jdbc, ObjectProvider<MemberPhones> phones, SensitiveCipher cipher, CardActions cardActions,
            AuditLog audit, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.phones = phones;
        this.cipher = cipher;
        this.cardActions = cardActions;
        this.audit = audit;
        this.clock = clock;
    }

    public ClaimResult claim(String memberId, String orderNo, String phone) {
        String externalOrderId = orderNo == null ? "" : orderNo.trim();
        String given = Phones.normalize(phone);
        if (externalOrderId.isEmpty() || given == null) {
            throw new UserFacingException("请填写渠道订单号与收货手机号");
        }
        MemberPhones memberPhones = phones.getIfAvailable();
        String bound = memberPhones == null ? null : memberPhones.boundPhone(memberId).map(Phones::normalize).orElse(null);
        if (bound == null) {
            throw new UserFacingException("请先给当前账号绑定手机号");
        }
        if (!bound.equals(given)) {
            throw new UserFacingException("手机号与当前账号绑定的手机号不一致");
        }
        byte[] hash = cipher.hash(SensitiveField.BUYER_CONTACT, given);
        List<Long> candidates = jdbc.sql("""
                SELECT o.id FROM lr_channel_order o JOIN lr_channel c ON c.id = o.channel_id
                 WHERE o.external_order_id = ? AND o.buyer_contact_hash = ? AND c.buyer_can_view_usage = 1 ORDER BY o.id
                """).params(externalOrderId, hash).query(Long.class).list();
        if (candidates.isEmpty()) {
            throw new UserFacingException(NOT_FOUND);
        }
        long orderId = candidates.getFirst();
        String actor = "member:" + memberId;
        return tx.execute(status -> {
            Claimable order = jdbc.sql("""
                    SELECT o.id, o.external_order_id, o.status, o.claimed_member_id,
                           (SELECT COALESCE(SUM(l.qty), 0) FROM lr_channel_order_line l WHERE l.channel_order_id = o.id) AS qty
                      FROM lr_channel_order o WHERE o.id = ? FOR UPDATE
                    """).param(orderId).query(Claimable.class).single();
            if (order.claimedMemberId() != null) {
                throw new UserFacingException("该订单已被认领");
            }
            List<Long> cards = jdbc.sql("""
                    SELECT c.id FROM lr_card_asset c
                      JOIN lr_fulfillment_line fl ON fl.id = c.fulfillment_line_id
                      JOIN lr_channel_order_line l ON l.id = fl.channel_order_line_id
                     WHERE l.channel_order_id = ? ORDER BY c.id
                    """).param(orderId).query(Long.class).list();
            boolean admitted = "FULFILLING".equals(order.status()) || "DONE".equals(order.status());
            if (!admitted || cards.size() < order.qty()) {
                throw new UserFacingException("订单还在处理中，请稍后再认领");
            }
            cards.forEach(card -> cardActions.claimOwner(card, memberId, actor));
            jdbc.sql("""
                    UPDATE lr_channel_order SET claimed_member_id = ?, claimed_at = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?
                    """).params(memberId, UtcColumns.toColumn(clock.instant()), orderId).update();
            audit.record(actor, "CHANNEL_ORDER_CLAIM", "CHANNEL_ORDER", Long.toString(orderId), null,
                    Json.write(Map.of("memberId", memberId, "cards", cards.size())));
            return new ClaimResult(order.externalOrderId(), cards.size());
        });
    }
}
