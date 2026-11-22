package com.liroam.ops.keys;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveCipher.Sealed;
import com.liroam.common.ops.OpsAlert;
import com.liroam.common.ops.OpsAlert.Level;
import com.liroam.common.ops.OpsAlertSource;
import com.liroam.common.web.UserFacingException;
import com.liroam.schema.SealedTables;
import com.liroam.schema.SealedTables.SealedColumn;
import com.liroam.schema.SealedTables.SealedTable;

/**
 * 数据密钥轮换（运维手册「数据密钥轮换」）：新版本 {@code LIROAM_DATA_KEY_Vn} 上线后新写入即用新版本；
 * 这里把旧版本加密的行分批重加密到当前版本，全部完成后旧版本密钥才可下线。查询哈希不变（哈希密钥不轮换）。
 * 行上的密钥版本不在密钥环里 = 这些数据当前解不开，出严重告警。
 */
public class DataKeyRotation implements OpsAlertSource {

    public static final int MAX_BATCH = 1000;

    public record VersionCount(int keyVersion, long rows, boolean available) {
    }

    public record TableStatus(String table, List<VersionCount> versions, long staleRows, long unreadableRows) {
    }

    /**
     * @param staleRows      在密钥环里、但不是当前版本的行（可重加密）
     * @param unreadableRows 版本不在密钥环里的行（解不开）
     */
    public record Status(int currentVersion, SortedSet<Integer> availableVersions, List<TableStatus> tables, long staleRows,
            long unreadableRows) {
    }

    public record ResealResult(int currentVersion, long resealed, long remaining, Map<String, Long> byTable) {
    }

    private final JdbcClient jdbc;
    private final SensitiveCipher cipher;
    private final List<SealedTable> tables;
    private final AuditLog audit;

    public DataKeyRotation(JdbcClient jdbc, SensitiveCipher cipher, List<SealedTable> tables, AuditLog audit) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.tables = List.copyOf(tables);
        this.audit = audit;
    }

    public Status status() {
        Set<Integer> available = cipher.versions();
        List<TableStatus> result = new ArrayList<>();
        long stale = 0;
        long unreadable = 0;
        for (SealedTable t : tables) {
            List<VersionCount> versions = jdbc.sql("SELECT " + SealedTables.KEY_VERSION_COLUMN + " AS v, COUNT(*) AS n FROM " + t.table()
                    + " WHERE " + SealedTables.KEY_VERSION_COLUMN + " IS NOT NULL GROUP BY v ORDER BY v")
                    .query((rs, i) -> new VersionCount(rs.getInt("v"), rs.getLong("n"), available.contains(rs.getInt("v")))).list();
            long tStale = versions.stream().filter(v -> v.available() && v.keyVersion() != cipher.currentVersion()).mapToLong(VersionCount::rows).sum();
            long tUnreadable = versions.stream().filter(v -> !v.available()).mapToLong(VersionCount::rows).sum();
            result.add(new TableStatus(t.table(), versions, tStale, tUnreadable));
            stale += tStale;
            unreadable += tUnreadable;
        }
        return new Status(cipher.currentVersion(), cipher.versions(), result, stale, unreadable);
    }

    /** 重加密至多 {@code batchSize} 行（逐行更新，按原版本做乐观校验，可与业务写入并发、可重复执行）。 */
    public ResealResult reseal(int batchSize, String actor) {
        if (batchSize < 1 || batchSize > MAX_BATCH) {
            throw new UserFacingException("每批行数应在 1～" + MAX_BATCH);
        }
        List<Integer> old = cipher.versions().stream().filter(v -> v != cipher.currentVersion()).toList();
        Map<String, Long> byTable = new LinkedHashMap<>();
        long total = 0;
        if (!old.isEmpty()) {
            String in = old.stream().map(String::valueOf).collect(Collectors.joining(","));
            for (SealedTable t : tables) {
                if (total >= batchSize) {
                    break;
                }
                long n = resealTable(t, in, (int) (batchSize - total));
                if (n > 0) {
                    byTable.put(t.table(), n);
                    total += n;
                }
            }
        }
        if (total > 0) {
            audit.record(actor, "DATA_KEY_RESEAL", "DATA_KEY", "V" + cipher.currentVersion(), null,
                    "{\"resealed\":" + total + ",\"tables\":\"" + String.join(",", byTable.keySet()) + "\"}");
        }
        return new ResealResult(cipher.currentVersion(), total, status().staleRows(), byTable);
    }

    private long resealTable(SealedTable t, String oldVersions, int limit) {
        String cols = t.columns().stream().map(SealedColumn::column).collect(Collectors.joining(", "));
        List<Map<String, Object>> rows = jdbc.sql("SELECT id, " + SealedTables.KEY_VERSION_COLUMN + ", " + cols + " FROM " + t.table()
                + " WHERE " + SealedTables.KEY_VERSION_COLUMN + " IN (" + oldVersions + ") ORDER BY id LIMIT " + limit).query().listOfRows();
        String set = t.columns().stream().map(c -> c.column() + " = ?").collect(Collectors.joining(", "));
        String update = "UPDATE " + t.table() + " SET " + set + ", " + SealedTables.KEY_VERSION_COLUMN + " = ? WHERE id = ? AND "
                + SealedTables.KEY_VERSION_COLUMN + " = ?";
        long done = 0;
        for (Map<String, Object> row : rows) {
            int version = ((Number) row.get(SealedTables.KEY_VERSION_COLUMN)).intValue();
            List<Object> params = new ArrayList<>();
            for (SealedColumn c : t.columns()) {
                byte[] bytes = (byte[]) row.get(c.column());
                params.add(bytes == null ? null : cipher.seal(c.field(), cipher.open(c.field(), new Sealed(version, bytes))).bytes());
            }
            params.add(cipher.currentVersion());
            params.add(row.get("id"));
            params.add(version);
            done += jdbc.sql(update).params(params).update();
        }
        return done;
    }

    @Override
    public List<OpsAlert> check(Instant now) {
        Status s = status();
        List<OpsAlert> alerts = new ArrayList<>();
        if (s.unreadableRows() > 0) {
            TreeSet<Integer> missing = s.tables().stream().flatMap(t -> t.versions().stream()).filter(v -> !v.available())
                    .map(VersionCount::keyVersion).collect(Collectors.toCollection(TreeSet::new));
            alerts.add(new OpsAlert("DATA_KEY_UNREADABLE", Level.CRITICAL, "敏感数据解不开",
                    "这些行用的密钥版本未配置：" + missing.stream().map(v -> SensitiveCipher.DATA_KEY_PREFIX + v).collect(Collectors.joining("、"))
                            + "；恢复对应环境变量并重启", s.unreadableRows()));
        }
        if (s.staleRows() > 0) {
            alerts.add(new OpsAlert("DATA_KEY_STALE", Level.WARN, "敏感数据仍用旧密钥版本",
                    "在「运维概览」分批重加密到 V" + s.currentVersion() + "，完成后旧版本密钥才能下线", s.staleRows()));
        }
        return alerts;
    }
}
