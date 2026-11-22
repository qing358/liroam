package com.liroam.fulfillment.poll;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.liroam.common.setting.SettingKey;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;

/**
 * 轮询分层（domain-design §6.4）：已交码未激活 6h、使用中 30m、临期 15m、暂停 24h、到期后 7d（30 天后停）；未交码与终态不轮询。
 * 间隔取 {@code poll.tier.*}（供应商可覆盖）。
 */
public class PollTiers {

    public enum Tier {
        NOT_ACTIVATED(FulfillmentSettings.POLL_TIER_NOT_ACTIVATED),
        IN_USE(FulfillmentSettings.POLL_TIER_IN_USE),
        NEAR_EXPIRY(FulfillmentSettings.POLL_TIER_NEAR_EXPIRY),
        SUSPENDED(FulfillmentSettings.POLL_TIER_SUSPENDED),
        EXPIRED(FulfillmentSettings.POLL_TIER_EXPIRED);

        private final SettingKey<Duration> interval;

        Tier(SettingKey<Duration> interval) {
            this.interval = interval;
        }
    }

    private final CardAssets cards;
    private final Settings settings;

    public PollTiers(CardAssets cards, Settings settings) {
        this.cards = cards;
        this.settings = settings;
    }

    public Optional<Tier> tierOf(Card card, Instant now) {
        SettingScope scope = SettingScope.supplier(card.supplierId());
        Instant expiresAt = UtcColumns.fromColumn(card.expiresAt());
        return switch (card.status()) {
            case DELIVERED -> Optional.of(Tier.NOT_ACTIVATED);
            case IN_USE -> expiresAt != null && expiresAt.minus(settings.get(FulfillmentSettings.POLL_TIER_NEAR_EXPIRY_WINDOW, scope)).isBefore(now)
                    ? Optional.of(Tier.NEAR_EXPIRY) : Optional.of(Tier.IN_USE);
            case SUSPENDED -> Optional.of(Tier.SUSPENDED);
            case EXPIRED -> expiresAt != null && expiresAt.plus(settings.get(FulfillmentSettings.POLL_TIER_EXPIRED_STOP_AFTER, scope)).isBefore(now)
                    ? Optional.empty() : Optional.of(Tier.EXPIRED);
            default -> Optional.empty();
        };
    }

    /** 按卡当前状态重排下次轮询；不该轮询的卡清空。 */
    public void reschedule(Card card, Instant now) {
        Optional<Tier> tier = tierOf(card, now);
        if (tier.isEmpty()) {
            cards.schedulePoll(card.id(), null, null);
            return;
        }
        Duration interval = settings.get(tier.get().interval, SettingScope.supplier(card.supplierId()));
        cards.schedulePoll(card.id(), tier.get().name(), now.plus(interval));
    }

    public static boolean pollable(CardAssetStatus status) {
        return status == CardAssetStatus.DELIVERED || status == CardAssetStatus.IN_USE || status == CardAssetStatus.SUSPENDED
                || status == CardAssetStatus.EXPIRED;
    }
}
