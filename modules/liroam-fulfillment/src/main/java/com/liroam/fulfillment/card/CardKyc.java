package com.liroam.fulfillment.card;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.credential.CredentialDeliveries;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.KycStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;

/**
 * 卡的实名：买家自报（本站不收证件）、运营核验或驳回。状态过闸时，已开通且交码已到的卡补发交码事件，
 * 由 {@code DeliveryProgress} 统一推进到已交付。
 */
public class CardKyc {

    private static final Set<CardAssetStatus> CLOSED = EnumSet.of(CardAssetStatus.CANCELLED, CardAssetStatus.TERMINATED);

    private final TransactionOperations tx;
    private final CardAssets cards;
    private final CredentialDeliveries deliveries;
    private final AuditLog audit;

    public CardKyc(TransactionOperations tx, CardAssets cards, CredentialDeliveries deliveries, AuditLog audit) {
        this.tx = tx;
        this.cards = cards;
        this.deliveries = deliveries;
        this.audit = audit;
    }

    public void declare(long cardAssetId, String memberId) {
        tx.executeWithoutResult(s -> {
            Card card = cards.lock(cardAssetId).filter(c -> Objects.equals(c.ownerMemberId(), memberId))
                    .orElseThrow(() -> new UserFacingException("找不到这张卡"));
            KycStatus from = requireOpen(card);
            if (from == KycStatus.VERIFIED) {
                throw new UserFacingException("实名已核验，无需再次提交");
            }
            if (from == KycStatus.USER_DECLARED) {
                return;
            }
            move(card, from, KycStatus.USER_DECLARED, memberId, "KYC_DECLARE", null);
        });
    }

    public void review(long cardAssetId, KycStatus result, String note, String operator) {
        if (result != KycStatus.VERIFIED && result != KycStatus.REJECTED) {
            throw new UserFacingException("核验结果只能是通过或驳回");
        }
        tx.executeWithoutResult(s -> {
            Card card = cards.lock(cardAssetId).orElseThrow(() -> new UserFacingException("找不到这张卡"));
            KycStatus from = requireOpen(card);
            if (from == result) {
                return;
            }
            if (!KycStatus.MACHINE.canMove(from, result)) {
                throw new UserFacingException("实名已核验，不能再改为驳回");
            }
            move(card, from, result, operator, "KYC_REVIEW", note);
        });
    }

    private static KycStatus requireOpen(Card card) {
        KycStatus status = KycStatus.valueOf(card.kycStatus());
        if (status == KycStatus.NOT_REQUIRED) {
            throw new UserFacingException("这张卡无需实名");
        }
        if (CLOSED.contains(card.status())) {
            throw new UserFacingException("这张卡已作废");
        }
        return status;
    }

    private void move(Card card, KycStatus from, KycStatus to, String actor, String action, String note) {
        cards.moveKyc(card.id(), from, to);
        audit.record(actor, action, "CARD", Long.toString(card.id()), "{\"kyc\":\"" + from + "\"}",
                "{\"kyc\":\"" + to + "\"" + (note == null || note.isBlank() ? "" : ",\"note\":" + json(note)) + "}");
        if (to.passesGate() && card.status() == CardAssetStatus.PROVISIONED) {
            deliveries.announceExisting(card.id());
        }
    }

    private static String json(String text) {
        return "\"" + text.trim().replaceAll("\\p{Cntrl}", " ").replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
