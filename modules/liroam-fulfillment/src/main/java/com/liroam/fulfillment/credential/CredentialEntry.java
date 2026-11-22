package com.liroam.fulfillment.credential;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;

/** MANUAL 通道：运营在后台给一张卡录入激活码（首次交付或替换）。 */
public class CredentialEntry {

    private static final Set<CardAssetStatus> ACCEPTS_CREDENTIAL = EnumSet.of(CardAssetStatus.ALLOCATED, CardAssetStatus.PROVISIONED,
            CardAssetStatus.DELIVERED, CardAssetStatus.IN_USE, CardAssetStatus.SUSPENDED);

    /** 能收交码的卡：已分配（开通后自动交付）、已开通、开通后转异常、已交付（替换）。 */
    static boolean accepts(Card card) {
        return ACCEPTS_CREDENTIAL.contains(card.status())
                || card.status() == CardAssetStatus.EXCEPTION && card.exceptionFrom() == CardAssetStatus.PROVISIONED;
    }

    private final TransactionOperations tx;
    private final CardAssets cards;
    private final CredentialDeliveries deliveries;
    private final AuditLog audit;

    public CredentialEntry(TransactionOperations tx, CardAssets cards, CredentialDeliveries deliveries, AuditLog audit) {
        this.tx = tx;
        this.cards = cards;
        this.deliveries = deliveries;
        this.audit = audit;
    }

    public void enter(long cardAssetId, String lpa, String actor) {
        if (lpa == null || lpa.isBlank()) {
            throw new UserFacingException("请填写激活码");
        }
        tx.executeWithoutResult(status -> {
            Card card = cards.lock(cardAssetId).orElseThrow(() -> new UserFacingException("找不到这张卡"));
            if (!accepts(card)) {
                throw new UserFacingException("这张卡当前状态不能录入激活码");
            }
            deliveries.deliver(card.id(), CredentialResult.Kind.LPA, lpa.trim(), CredentialChannel.MANUAL, actor);
            audit.record(actor, "CREDENTIAL_ENTER", "CARD", Long.toString(card.id()), null, null);
        });
    }
}
