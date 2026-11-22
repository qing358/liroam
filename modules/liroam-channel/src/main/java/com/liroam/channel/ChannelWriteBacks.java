package com.liroam.channel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.channel.ChannelDirectory.Channel;
import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.channel.spi.ExternalOrderRef;
import com.liroam.channel.spi.WriteBack;
import com.liroam.channel.spi.WriteBack.DeliveredCard;
import com.liroam.channel.spi.WriteBackOutcome;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.CredentialDelivered;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardQueries.LineCredential;
import com.liroam.fulfillment.task.ManualTasks;

/**
 * 交码回传（domain-design §11）：渠道单行对应的履约行全部交码（COMPLETED）后行 PENDING → READY；节拍调适配器 {@code writeBack}。
 * 成功 → DONE（整单各行都 DONE → 渠道单 DONE）；可重试失败退避重试，超过 {@link ChannelSettings#WRITEBACK_MAX_ATTEMPTS} 或被渠道拒绝 → MANUAL + 待办。
 */
public class ChannelWriteBacks {

    private static final Logger log = LoggerFactory.getLogger(ChannelWriteBacks.class);

    static final String TASK_REF = "CHANNEL_ORDER_LINE";
    /** 认领一行后先占住这么久，防止多 worker 重复回传；回传结束即改写。 */
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private record Due(long id, long channelId, String externalOrderId, String externalLineNo, String externalSkuId, int writebackAttempts,
            long fulfillmentLineId) {
    }

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final ChannelDirectory channels;
    private final ChannelAdapterRegistry adapters;
    private final CardQueries cards;
    private final Settings settings;
    private final ManualTasks tasks;
    private final AuditLog audit;
    private final Clock clock;

    public ChannelWriteBacks(TransactionTemplate tx, JdbcClient jdbc, ChannelDirectory channels, ChannelAdapterRegistry adapters, CardQueries cards,
            Settings settings, ManualTasks tasks, AuditLog audit, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.channels = channels;
        this.adapters = adapters;
        this.cards = cards;
        this.settings = settings;
        this.tasks = tasks;
        this.audit = audit;
        this.clock = clock;
    }

    /** 交码事务提交前（履约行推进已在同一事务内完成）把整行交齐的渠道单行置 READY；漏掉的由 {@link #runOnce} 兜底扫描。 */
    @EventListener
    public void onCredentialDelivered(CredentialDelivered event) {
        Optional<Long> line = jdbc.sql("""
                SELECT fl.channel_order_line_id FROM lr_card_asset c JOIN lr_fulfillment_line fl ON fl.id = c.fulfillment_line_id
                 WHERE c.id = ? AND fl.channel_order_line_id IS NOT NULL
                """).param(event.cardAssetId()).query(Long.class).optional();
        if (line.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    markReady(line.get());
                }
            });
        } else {
            markReady(line.get());
        }
    }

    /** @return 本轮尝试回传的行数 */
    public int runOnce(int batch) {
        markReady(null);
        Instant now = clock.instant();
        List<Due> due = jdbc.sql("""
                SELECT l.id, o.channel_id, o.external_order_id, l.external_line_no, l.external_sku_id, l.writeback_attempts,
                       fl.id AS fulfillment_line_id
                  FROM lr_channel_order_line l
                  JOIN lr_channel_order o ON o.id = l.channel_order_id
                  JOIN lr_fulfillment_line fl ON fl.channel_order_line_id = l.id
                 WHERE l.writeback_status IN ('READY', 'FAILED') AND (l.writeback_next_at IS NULL OR l.writeback_next_at <= ?)
                 ORDER BY l.id LIMIT ?
                """).params(UtcColumns.toColumn(now), batch).query(Due.class).list();
        int attempted = 0;
        for (Due line : due) {
            try {
                if (writeBack(line, now)) {
                    attempted++;
                }
            } catch (RuntimeException e) {
                log.error("渠道单 {} 行 {} 回传异常，下轮重试", line.externalOrderId(), line.externalLineNo(), e);
            }
        }
        return attempted;
    }

    /** 后台对转人工的回传再试一次：重置次数，关掉对应待办。 */
    public void retry(long lineId, String actor) {
        tx.executeWithoutResult(status -> {
            String current = jdbc.sql("SELECT writeback_status FROM lr_channel_order_line WHERE id = ? FOR UPDATE").param(lineId).query(String.class)
                    .optional().orElseThrow(() -> new UserFacingException("找不到这条渠道单行"));
            if (!"MANUAL".equals(current)) {
                throw new UserFacingException("只有转人工的回传可以重试");
            }
            jdbc.sql("""
                    UPDATE lr_channel_order_line SET writeback_status = 'READY', writeback_attempts = 0, writeback_next_at = NULL, writeback_error = NULL,
                           version = version + 1, updated_at = UTC_TIMESTAMP(3)
                     WHERE id = ?
                    """).param(lineId).update();
            jdbc.sql("SELECT id FROM lr_manual_task WHERE kind = ? AND ref_type = ? AND ref_id = ? AND status = 'OPEN'")
                    .params(ManualTasks.Kind.CHANNEL_WRITEBACK_FAILED.name(), TASK_REF, Long.toString(lineId)).query(Long.class).list()
                    .forEach(id -> tasks.resolve(id, "已人工重试回传", actor));
            audit.record(actor, "CHANNEL_WRITEBACK_RETRY", TASK_REF, Long.toString(lineId), "{\"writebackStatus\":\"MANUAL\"}",
                    "{\"writebackStatus\":\"READY\"}");
        });
    }

    /** @param lineId 为空时扫描全部 PENDING 行 */
    private void markReady(Long lineId) {
        jdbc.sql("""
                UPDATE lr_channel_order_line l JOIN lr_fulfillment_line fl ON fl.channel_order_line_id = l.id
                   SET l.writeback_status = 'READY', l.writeback_next_at = NULL, l.version = l.version + 1, l.updated_at = UTC_TIMESTAMP(3)
                 WHERE l.writeback_status = 'PENDING' AND fl.status = 'COMPLETED'
                """ + (lineId == null ? "" : " AND l.id = " + lineId)).update();
    }

    private boolean writeBack(Due line, Instant now) {
        Channel channel = channels.require(line.channelId());
        Optional<ChannelAdapter> adapter = adapters.find(channel.adapterKey());
        if (adapter.isEmpty()) {
            return false;
        }
        int claimed = jdbc.sql("""
                UPDATE lr_channel_order_line SET writeback_next_at = ? WHERE id = ? AND writeback_status IN ('READY', 'FAILED')
                   AND (writeback_next_at IS NULL OR writeback_next_at <= ?)
                """).params(UtcColumns.toColumn(now.plus(LEASE)), line.id(), UtcColumns.toColumn(now)).update();
        if (claimed != 1) {
            return false;
        }
        List<DeliveredCard> delivered = cards.credentialsOfLine(line.fulfillmentLineId(), "channel:" + channel.code()).stream()
                .map(ChannelWriteBacks::delivered).toList();
        ExternalOrderRef ref = new ExternalOrderRef(line.externalOrderId(), line.externalLineNo(), line.externalSkuId());
        WriteBackOutcome outcome;
        try {
            outcome = adapter.get().writeBack(ref, new WriteBack(delivered));
        } catch (RuntimeException e) {
            outcome = new WriteBackOutcome.Retryable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        int attempts = line.writebackAttempts() + 1;
        switch (outcome) {
            case WriteBackOutcome.Ok ok -> done(line, attempts);
            case WriteBackOutcome.Retryable r -> {
                int max = settings.get(ChannelSettings.WRITEBACK_MAX_ATTEMPTS, new SettingScope(SettingScope.Type.CHANNEL, channel.id()));
                if (attempts >= max) {
                    manual(line, attempts, r.reason(), "回传失败 " + attempts + " 次：" + r.reason());
                } else {
                    Duration base = settings.get(ChannelSettings.WRITEBACK_BACKOFF_BASE, new SettingScope(SettingScope.Type.CHANNEL, channel.id()));
                    jdbc.sql("""
                            UPDATE lr_channel_order_line SET writeback_status = 'FAILED', writeback_attempts = ?, writeback_error = ?, writeback_next_at = ?,
                                   version = version + 1, updated_at = UTC_TIMESTAMP(3)
                             WHERE id = ?
                            """).params(attempts, ChannelOrders.truncate(r.reason()), UtcColumns.toColumn(now.plus(backoff(base, attempts))), line.id())
                            .update();
                }
            }
            case WriteBackOutcome.Rejected r -> manual(line, attempts, r.message() + "（" + r.code() + "）", "渠道拒绝回传：" + r.message());
        }
        return true;
    }

    private void done(Due line, int attempts) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    UPDATE lr_channel_order_line SET writeback_status = 'DONE', writeback_attempts = ?, writeback_error = NULL, writeback_next_at = NULL,
                           written_back_at = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3)
                     WHERE id = ?
                    """).params(attempts, UtcColumns.toColumn(clock.instant()), line.id()).update();
            jdbc.sql("""
                    UPDATE lr_channel_order o SET o.status = 'DONE', o.version = o.version + 1, o.updated_at = UTC_TIMESTAMP(3)
                     WHERE o.id = (SELECT channel_order_id FROM lr_channel_order_line WHERE id = ?) AND o.status = 'FULFILLING'
                       AND NOT EXISTS (SELECT 1 FROM lr_channel_order_line l WHERE l.channel_order_id = o.id AND l.writeback_status <> 'DONE')
                    """).param(line.id()).update();
        });
    }

    private void manual(Due line, int attempts, String error, String note) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    UPDATE lr_channel_order_line SET writeback_status = 'MANUAL', writeback_attempts = ?, writeback_error = ?, writeback_next_at = NULL,
                           version = version + 1, updated_at = UTC_TIMESTAMP(3)
                     WHERE id = ?
                    """).params(attempts, ChannelOrders.truncate(error), line.id()).update();
            tasks.open(ManualTasks.Kind.CHANNEL_WRITEBACK_FAILED, TASK_REF, line.id(),
                    "渠道单 " + line.externalOrderId() + " 行 " + line.externalLineNo() + " " + note + "；处理后在渠道订单页重试回传", clock.instant());
        });
    }

    private static Duration backoff(Duration base, int attempts) {
        Duration d = base.multipliedBy(1L << Math.min(attempts - 1, 16));
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private static DeliveredCard delivered(LineCredential c) {
        var cred = c.credential();
        boolean shipped = cred.logisticsNo() != null;
        return new DeliveredCard(c.cardNo(), c.form(), cred.kind(), shipped ? null : cred.payload(), cred.logisticsCompany(), cred.logisticsNo());
    }
}
