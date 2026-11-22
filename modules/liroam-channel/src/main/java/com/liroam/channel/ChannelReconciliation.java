package com.liroam.channel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.channel.ChannelDirectory.Channel;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.pricing.Json;

/**
 * 渠道日对账（domain-design §12）：渠道某日结算单 vs 本站该日（北京时间）渠道单与履约结果。
 * 差异四类：本站漏单 MISSING_OURS、渠道无此单 MISSING_THEIRS、金额不一致 AMOUNT、未履约完成 STATUS；每条差异开一条 RECON_DIFF 待办。
 * 每次运行一个 {@code lr_recon_run}（kind = CHANNEL_DAILY，period = 日期，scope_ref = 渠道编码；summary_json 的 matched / diffs 与支付、供应商日对账同口径）；渠道单上记最近一次对账结果。
 */
public class ChannelReconciliation {

    private static final Logger log = LoggerFactory.getLogger(ChannelReconciliation.class);

    static final String KIND = "CHANNEL_DAILY";
    private static final String CURRENCY = "CNY";

    public record ReconSummary(long runId, int theirCount, int ourCount, int diffCount) {
    }

    public record DiffView(long id, String diffKind, String diffText, String ourRef, String theirRef, Long ourAmountFen, Long theirAmountFen,
            String status, String createdText) {
    }

    private record Ours(long id, String externalOrderId, long paidFen, boolean fulfilled) {
    }

    private record DiffRow(long id, String diffKind, String ourRef, String theirRef, Long ourAmountMinor, Long theirAmountMinor, String status,
            LocalDateTime createdAt) {
    }

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final ChannelDirectory channels;
    private final ChannelAdapterRegistry adapters;
    private final ManualTasks tasks;
    private final AuditLog audit;
    private final Clock clock;

    public ChannelReconciliation(TransactionTemplate tx, JdbcClient jdbc, ChannelDirectory channels, ChannelAdapterRegistry adapters, ManualTasks tasks,
            AuditLog audit, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.channels = channels;
        this.adapters = adapters;
        this.tasks = tasks;
        this.audit = audit;
        this.clock = clock;
    }

    public ReconSummary run(long channelId, LocalDate day, String actor) {
        Channel channel = channels.require(channelId);
        if (adapters.find(channel.adapterKey()).isEmpty()) {
            throw new UserFacingException("渠道 " + channel.code() + " 的适配器 " + channel.adapterKey() + " 未装配，不能对账");
        }
        Map<String, Long> theirs = new LinkedHashMap<>();
        for (ExternalStatementLine line : adapters.require(channel).statement(day)) {
            theirs.merge(line.externalOrderId(), line.paidFen(), Long::sum);
        }
        ReconSummary summary = tx.execute(status -> reconcile(channel, day, theirs, actor));
        audit.record(actor, "CHANNEL_RECON_RUN", "RECON_RUN", Long.toString(summary.runId()), null,
                "{\"channelId\":" + channelId + ",\"day\":\"" + day + "\",\"diffCount\":" + summary.diffCount() + "}");
        return summary;
    }

    /** worker 节拍：每个启用渠道对一次昨天（北京时间）的账，已对过的跳过。 @return 本轮对账的渠道数 */
    public int runDueDaily() {
        LocalDate yesterday = LocalDate.ofInstant(clock.instant(), DisplayTime.ZONE).minusDays(1);
        int ran = 0;
        for (Channel channel : channels.enabled()) {
            boolean done = jdbc.sql("""
                    SELECT COUNT(*) FROM lr_recon_run WHERE kind = ? AND period = ? AND JSON_EXTRACT(summary_json, '$.channelId') = ?
                    """).params(KIND, yesterday.toString(), channel.id()).query(Integer.class).single() > 0;
            if (done) {
                continue;
            }
            try {
                run(channel.id(), yesterday, "SYSTEM");
                ran++;
            } catch (RuntimeException e) {
                log.error("渠道 {} {} 对账失败，下轮重试", channel.code(), yesterday, e);
            }
        }
        return ran;
    }

    public List<DiffView> diffs(long runId) {
        return jdbc.sql("""
                SELECT id, diff_kind, our_ref, their_ref, our_amount_minor, their_amount_minor, status, created_at FROM lr_recon_diff
                 WHERE run_id = ? ORDER BY id
                """).param(runId).query(DiffRow.class).list().stream()
                .map(d -> new DiffView(d.id(), d.diffKind(), diffText(d.diffKind()), d.ourRef(), d.theirRef(), d.ourAmountMinor(), d.theirAmountMinor(),
                        d.status(), DisplayTime.format(UtcColumns.fromColumn(d.createdAt()))))
                .toList();
    }

    private ReconSummary reconcile(Channel channel, LocalDate day, Map<String, Long> theirs, String actor) {
        Instant from = day.atStartOfDay(DisplayTime.ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(DisplayTime.ZONE).toInstant();
        Map<String, Ours> ours = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.external_order_id, o.paid_fen,
                       NOT EXISTS (SELECT 1 FROM lr_channel_order_line l LEFT JOIN lr_fulfillment_line fl ON fl.channel_order_line_id = l.id
                                    WHERE l.channel_order_id = o.id AND (fl.id IS NULL OR fl.status <> 'COMPLETED')) AS fulfilled
                  FROM lr_channel_order o
                 WHERE o.channel_id = :channel AND ((COALESCE(o.ordered_at, o.created_at) >= :from AND COALESCE(o.ordered_at, o.created_at) < :to)
                """);
        if (!theirs.isEmpty()) {
            sql.append(" OR o.external_order_id IN (:theirIds)");
        }
        sql.append(") ORDER BY o.id");
        var spec = jdbc.sql(sql.toString()).param("channel", channel.id()).param("from", UtcColumns.toColumn(from)).param("to", UtcColumns.toColumn(to));
        if (!theirs.isEmpty()) {
            spec = spec.param("theirIds", new ArrayList<>(theirs.keySet()));
        }
        spec.query(Ours.class).list().forEach(o -> ours.put(o.externalOrderId(), o));

        Instant now = clock.instant();
        long runId = insertRun(channel, day, actor, now);
        Map<String, Integer> byKind = new LinkedHashMap<>();
        Map<Long, Boolean> balanced = new LinkedHashMap<>();
        ours.values().forEach(o -> balanced.put(o.id(), true));
        for (Map.Entry<String, Long> their : theirs.entrySet()) {
            Ours our = ours.get(their.getKey());
            if (our == null) {
                diff(runId, channel, day, "MISSING_OURS", null, their.getKey(), null, their.getValue(), now, byKind);
                continue;
            }
            if (our.paidFen() != their.getValue()) {
                diff(runId, channel, day, "AMOUNT", our.externalOrderId(), their.getKey(), our.paidFen(), their.getValue(), now, byKind);
                balanced.put(our.id(), false);
            }
            if (!our.fulfilled()) {
                diff(runId, channel, day, "STATUS", our.externalOrderId(), their.getKey(), our.paidFen(), their.getValue(), now, byKind);
                balanced.put(our.id(), false);
            }
        }
        for (Ours our : ours.values()) {
            if (!theirs.containsKey(our.externalOrderId())) {
                diff(runId, channel, day, "MISSING_THEIRS", our.externalOrderId(), null, our.paidFen(), null, now, byKind);
                balanced.put(our.id(), false);
            }
        }
        balanced.forEach((orderId, ok) -> jdbc.sql("""
                UPDATE lr_channel_order SET recon_status = ?, recon_run_id = ?, reconciled_at = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3)
                 WHERE id = ?
                """).params(ok ? "BALANCED" : "DIFF", runId, UtcColumns.toColumn(now), orderId).update());
        int diffCount = byKind.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("channelId", channel.id());
        summary.put("channelCode", channel.code());
        summary.put("theirCount", theirs.size());
        summary.put("ourCount", ours.size());
        summary.put("diffCount", diffCount);
        summary.put("diffsByKind", byKind);
        summary.put("matched", balanced.values().stream().filter(Boolean::booleanValue).count());
        summary.put("diffs", diffCount);
        jdbc.sql("UPDATE lr_recon_run SET status = ?, summary_json = CAST(? AS JSON), updated_at = UTC_TIMESTAMP(3) WHERE id = ?")
                .params(diffCount == 0 ? "BALANCED" : "DIFF", Json.write(summary), runId).update();
        return new ReconSummary(runId, theirs.size(), ours.size(), diffCount);
    }

    private long insertRun(Channel channel, LocalDate day, String actor, Instant now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_recon_run (kind, period, scope_ref, status, created_by, created_at, updated_at) VALUES (?, ?, ?, 'RUNNING', ?, ?, ?)
                """).params(KIND, day.toString(), channel.code(), actor, UtcColumns.toColumn(now), UtcColumns.toColumn(now)).update(keys);
        return keys.getKey().longValue();
    }

    private void diff(long runId, Channel channel, LocalDate day, String kind, String ourRef, String theirRef, Long ourFen, Long theirFen, Instant now,
            Map<String, Integer> byKind) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_recon_diff (run_id, diff_kind, our_ref, their_ref, currency, our_amount_minor, their_amount_minor)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(runId, kind, ourRef, theirRef, CURRENCY, ourFen, theirFen).update(keys);
        long diffId = keys.getKey().longValue();
        String ref = ourRef != null ? ourRef : theirRef;
        tasks.open(ManualTasks.Kind.RECON_DIFF, "RECON_DIFF", diffId, "渠道 " + channel.code() + " " + day + " 对账：" + diffText(kind) + " " + ref
                + "（本站 " + yuan(ourFen) + " / 渠道 " + yuan(theirFen) + "）", now);
        byKind.merge(kind, 1, Integer::sum);
    }

    static String diffText(String kind) {
        return switch (kind) {
            case "MISSING_OURS" -> "本站漏单";
            case "MISSING_THEIRS" -> "渠道无此单";
            case "AMOUNT" -> "金额不一致";
            case "STATUS" -> "未履约完成";
            default -> kind;
        };
    }

    private static String yuan(Long fen) {
        return fen == null ? "—" : String.format("%d.%02d 元", fen / 100, fen % 100);
    }
}
