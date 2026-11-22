package com.liroam.ops.recon;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;

/** 对账批次与差异（{@code lr_recon_run} / {@code lr_recon_diff}）：每次导入一批，重跑留历史；差异人工处理一次并记审计。 */
public class ReconRuns {

    public enum DiffKind { MISSING_OURS, MISSING_THEIRS, AMOUNT }

    public record Run(long id, String kind, String period, String scopeRef, String status, int matched, int diffCount, long openDiffs,
            String createdBy, String createdAt) {
    }

    public record Diff(long id, long runId, String diffKind, String ourRef, String theirRef, String currency, Long ourAmountMinor,
            Long theirAmountMinor, String status, String resolution, String resolver) {
    }

    record NewDiff(DiffKind kind, String ourRef, String theirRef, Long ourAmountMinor, Long theirAmountMinor) {
    }

    private record RunRow(long id, String kind, String period, String scopeRef, String status, Integer matched, Integer diffCount, long openDiffs,
            String createdBy, LocalDateTime createdAt) {
    }

    private static final String RUN_SELECT = """
            SELECT r.id, r.kind, r.period, r.scope_ref, r.status,
                   CAST(JSON_EXTRACT(r.summary_json, '$.matched') AS SIGNED) AS matched,
                   CAST(JSON_EXTRACT(r.summary_json, '$.diffs') AS SIGNED) AS diff_count,
                   (SELECT COUNT(*) FROM lr_recon_diff d WHERE d.run_id = r.id AND d.status = 'OPEN') AS open_diffs,
                   r.created_by, r.created_at
              FROM lr_recon_run r
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Clock clock;

    public ReconRuns(TransactionOperations tx, JdbcClient jdbc, AuditLog audit, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** 调用方事务内：建批次、写差异；返回批次 id。 */
    long record(String kind, String period, String scopeRef, int matched, List<NewDiff> diffs, String currency, String actor) {
        LocalDateTime now = UtcColumns.toColumn(clock.instant());
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_recon_run (kind, period, scope_ref, status, summary_json, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, JSON_OBJECT('matched', ?, 'diffs', ?), ?, ?, ?)
                """).params(kind, period, scopeRef, diffs.isEmpty() ? "BALANCED" : "DIFF", matched, diffs.size(), actor, now, now).update(keys);
        long runId = keys.getKey().longValue();
        for (NewDiff d : diffs) {
            jdbc.sql("""
                    INSERT INTO lr_recon_diff (run_id, diff_kind, our_ref, their_ref, currency, our_amount_minor, their_amount_minor, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(runId, d.kind().name(), d.ourRef(), d.theirRef(), currency, d.ourAmountMinor(), d.theirAmountMinor(), now, now).update();
        }
        audit.record(actor, "RECON_RUN", "RECON_RUN", Long.toString(runId), null,
                "{\"kind\":\"" + kind + "\",\"period\":\"" + period + "\",\"matched\":" + matched + ",\"diffs\":" + diffs.size() + "}");
        return runId;
    }

    public Run run(long id) {
        return jdbc.sql(RUN_SELECT + " WHERE r.id = ?").param(id).query(RunRow.class).optional().map(ReconRuns::view)
                .orElseThrow(() -> new UserFacingException("对账批次不存在"));
    }

    public PageResult<Run> page(String kind, int pageNumber, int pageSize) {
        String where = kind == null || kind.isBlank() ? "" : " WHERE r.kind = ?";
        List<Object> args = new ArrayList<>();
        if (!where.isEmpty()) {
            args.add(kind.trim());
        }
        int size = PageResult.clampSize(pageSize);
        int current = Math.max(pageNumber, 1);
        long total = jdbc.sql("SELECT COUNT(*) FROM lr_recon_run r" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(PageResult.offset(current, size));
        List<Run> rows = jdbc.sql(RUN_SELECT + where + " ORDER BY r.id DESC LIMIT ? OFFSET ?").params(pageArgs).query(RunRow.class).list().stream()
                .map(ReconRuns::view).toList();
        return new PageResult<>(rows, total, current, size);
    }

    public List<Diff> diffs(long runId) {
        return jdbc.sql("""
                SELECT id, run_id, diff_kind, our_ref, their_ref, currency, our_amount_minor, their_amount_minor, status, resolution, resolver
                  FROM lr_recon_diff WHERE run_id = ? ORDER BY id
                """).param(runId).query(Diff.class).list();
    }

    /** @param status RESOLVED（已处理）/ IGNORED（无需处理） */
    public Diff resolve(long diffId, String status, String resolution, String actor) {
        if (!"RESOLVED".equals(status) && !"IGNORED".equals(status)) {
            throw new UserFacingException("处理结果只能是「已处理」或「忽略」");
        }
        if (resolution == null || resolution.isBlank()) {
            throw new UserFacingException("请写处理说明");
        }
        return tx.execute(s -> {
            int n = jdbc.sql("""
                    UPDATE lr_recon_diff SET status = ?, resolution = ?, resolver = ?, version = version + 1, updated_at = ?
                     WHERE id = ? AND status = 'OPEN'
                    """).params(status, resolution.trim(), actor, UtcColumns.toColumn(clock.instant()), diffId).update();
            if (n == 0) {
                throw new UserFacingException("差异不存在或已处理");
            }
            audit.record(actor, "RECON_DIFF_RESOLVE", "RECON_DIFF", Long.toString(diffId), "{\"status\":\"OPEN\"}", "{\"status\":\"" + status + "\"}");
            return jdbc.sql("""
                    SELECT id, run_id, diff_kind, our_ref, their_ref, currency, our_amount_minor, their_amount_minor, status, resolution, resolver
                      FROM lr_recon_diff WHERE id = ?
                    """).param(diffId).query(Diff.class).single();
        });
    }

    private static Run view(RunRow r) {
        return new Run(r.id(), r.kind(), r.period(), r.scopeRef(), r.status(), r.matched() == null ? 0 : r.matched(),
                r.diffCount() == null ? 0 : r.diffCount(), r.openDiffs(), r.createdBy(), DisplayTime.format(UtcColumns.fromColumn(r.createdAt())));
    }
}
