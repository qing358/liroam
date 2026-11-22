package com.liroam.fulfillment.poll;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.state.CommandPriority;
import com.liroam.fulfillment.state.CommandType;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.SupplierCommands;

/**
 * 轮询调度（domain-design §6.4）：到点（{@code next_poll_at}）的已交码卡各排一条 QUERY（优先级 POLL，经令牌桶的写优先预留），
 * 同时按分层把下次轮询往后排；查询结果由指令 worker 镜像并再按分层重排。已有未结束的查询不重复排。
 */
public class PollScheduler {

    private static final int BATCH = 200;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final CardAssets cards;
    private final SupplierCommands commands;
    private final PollTiers tiers;
    private final Settings settings;
    private final Clock clock;

    public PollScheduler(TransactionOperations tx, JdbcClient jdbc, CardAssets cards, SupplierCommands commands, PollTiers tiers, Settings settings,
            Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.cards = cards;
        this.commands = commands;
        this.tiers = tiers;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return 本轮排下的查询数 */
    public int runOnce() {
        Instant now = clock.instant();
        List<Long> due = jdbc.sql("""
                SELECT id FROM lr_card_asset
                 WHERE status IN (:statuses) AND next_poll_at IS NOT NULL AND next_poll_at <= :now
                 ORDER BY next_poll_at LIMIT :batch
                """).param("statuses", PollTiers.POLLABLE.stream().map(Enum::name).toList()).param("now", UtcColumns.toColumn(now))
                .param("batch", BATCH).query(Long.class).list();
        int queued = 0;
        for (long id : due) {
            Boolean done = tx.execute(status -> {
                Card card = cards.lock(id).orElseThrow();
                tiers.reschedule(card, now);
                if (tiers.tierOf(card, now).isEmpty()) {
                    return false;
                }
                commands.enqueue(CommandType.QUERY, card.supplierId(), card.id(), null, CommandPriority.POLL,
                        settings.get(FulfillmentSettings.COMMAND_MAX_ATTEMPTS, SettingScope.supplier(card.supplierId())), now);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                queued++;
            }
        }
        return queued;
    }
}
