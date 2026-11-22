package com.liroam.fulfillment.poll;

import java.time.Clock;
import java.time.Instant;

import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.state.CommandPriority;
import com.liroam.fulfillment.state.CommandType;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.SupplierCommands;

/** 按需刷新：买家打开卡详情时排一条高于轮询的 QUERY；冷却 {@code poll.on_demand_cooldown}（默认 5 分钟）内不重复排。 */
public class CardRefresh {

    private final TransactionOperations tx;
    private final CardAssets cards;
    private final SupplierCommands commands;
    private final Settings settings;
    private final Clock clock;

    public CardRefresh(TransactionOperations tx, CardAssets cards, SupplierCommands commands, Settings settings, Clock clock) {
        this.tx = tx;
        this.cards = cards;
        this.commands = commands;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return true = 已排刷新；false = 冷却中或卡不在可查询状态 */
    public boolean requestOnDemand(long cardAssetId) {
        Instant now = clock.instant();
        return Boolean.TRUE.equals(tx.execute(status -> {
            Card card = cards.find(cardAssetId).orElse(null);
            if (card == null || !PollTiers.pollable(card.status())) {
                return false;
            }
            if (!cards.startRefreshCooldown(card.id(), now, now.plus(settings.get(FulfillmentSettings.POLL_ON_DEMAND_COOLDOWN)), false)) {
                return false;
            }
            commands.enqueue(CommandType.QUERY, card.supplierId(), card.id(), null, CommandPriority.ON_DEMAND,
                    settings.get(FulfillmentSettings.COMMAND_MAX_ATTEMPTS, SettingScope.supplier(card.supplierId())), now);
            return true;
        }));
    }
}
