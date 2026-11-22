package com.liroam.fulfillment.delivery;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.event.EventListener;

import com.liroam.fulfillment.CredentialDelivered;
import com.liroam.fulfillment.poll.PollTiers;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.FulfillmentLines;

/**
 * 订阅 {@link CredentialDelivered}（交码事务内同步执行）：卡 PROVISIONED（或开通后转异常的卡）→ DELIVERED 并开始轮询，再推进履约行。
 * 实名门 BLOCK_DELIVERY 且实名未通过：交码内容已存，卡停在 PROVISIONED，买家端看不到交码，待实名通过后再推进。
 */
public class DeliveryProgress {

    private final CardAssets cards;
    private final FulfillmentLines lines;
    private final LineProgress lineProgress;
    private final PollTiers pollTiers;
    private final Clock clock;

    public DeliveryProgress(CardAssets cards, FulfillmentLines lines, LineProgress lineProgress, PollTiers pollTiers, Clock clock) {
        this.cards = cards;
        this.lines = lines;
        this.lineProgress = lineProgress;
        this.pollTiers = pollTiers;
        this.clock = clock;
    }

    @EventListener
    public void on(CredentialDelivered event) {
        Card card = cards.lock(event.cardAssetId()).orElseThrow();
        Instant now = clock.instant();
        if (card.status() == CardAssetStatus.EXCEPTION && card.exceptionFrom() == CardAssetStatus.PROVISIONED) {
            cards.move(card, CardAssetStatus.PROVISIONED, now);
            card = cards.find(card.id()).orElseThrow();
        }
        if (card.status() != CardAssetStatus.PROVISIONED || blockedByKyc(card)) {
            return;
        }
        cards.move(card, CardAssetStatus.DELIVERED, now);
        pollTiers.reschedule(cards.find(card.id()).orElseThrow(), now);
        lineProgress.refresh(card.fulfillmentLineId());
    }

    private boolean blockedByKyc(Card card) {
        return "BLOCK_DELIVERY".equals(lines.kycGateMode(card.fulfillmentLineId()))
                && !"NOT_REQUIRED".equals(card.kycStatus()) && !"APPROVED".equals(card.kycStatus());
    }
}
