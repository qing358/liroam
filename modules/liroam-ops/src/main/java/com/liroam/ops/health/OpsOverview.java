package com.liroam.ops.health;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.ops.OpsAlert;
import com.liroam.common.ops.OpsAlert.Level;
import com.liroam.common.ops.OpsAlertSource;
import com.liroam.common.setting.SettingKey;
import com.liroam.common.setting.Settings;

/**
 * 运维概览（运维手册「容量」「积压」）：整库占用与最大的几张表（壳表与自有表一起看）、各条待处理队列的积压数。
 * 表大小与行数取自 {@code information_schema}，InnoDB 下为估算值，有统计缓存（默认 24 小时）。
 */
public class OpsOverview implements OpsAlertSource {

    public static final int TOP_TABLES = 15;
    private static final long MB = 1024L * 1024;

    public static final SettingKey<Long> CAPACITY_MB = SettingKey.global("alert.db_capacity_mb", Long.class, 20480L,
            "数据库容量（MB），按生产库磁盘与备份空间核定；占用达 80% 出注意、达 100% 出严重");

    /** @param rows 估算行数 */
    public record TableSize(String table, long rows, long bytes) {
    }

    public record Backlog(String key, String label, long count) {
    }

    public record Overview(long databaseBytes, long capacityLimitBytes, List<TableSize> tables, List<Backlog> backlogs) {
    }

    private record BacklogQuery(String key, String label, String sql) {
    }

    private static final List<BacklogQuery> BACKLOGS = List.of(
            new BacklogQuery("SUPPLIER_COMMANDS", "待执行的供应商指令", "SELECT COUNT(*) FROM lr_supplier_command WHERE status IN ('PENDING', 'LOOKUP_PENDING')"),
            new BacklogQuery("MANUAL_TASKS", "打开的人工待办", "SELECT COUNT(*) FROM lr_manual_task WHERE status = 'OPEN'"),
            new BacklogQuery("SHELL_DELIVERIES", "待回写商城发货", "SELECT COUNT(*) FROM lr_shell_delivery WHERE status = 'PENDING'"),
            new BacklogQuery("CHANNEL_WRITEBACKS", "渠道回传失败或待人工", "SELECT COUNT(*) FROM lr_channel_order_line WHERE writeback_status IN ('FAILED', 'MANUAL')"),
            new BacklogQuery("RECON_DIFFS", "未处理的对账差异", "SELECT COUNT(*) FROM lr_recon_diff WHERE status = 'OPEN'"));

    private final JdbcClient jdbc;
    private final Settings settings;

    public OpsOverview(JdbcClient jdbc, Settings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Overview overview() {
        List<TableSize> tables = jdbc.sql("""
                SELECT table_name AS t, COALESCE(table_rows, 0) AS r, COALESCE(data_length, 0) + COALESCE(index_length, 0) AS b
                  FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                 ORDER BY b DESC, t LIMIT ?
                """).param(TOP_TABLES).query((rs, i) -> new TableSize(rs.getString("t"), rs.getLong("r"), rs.getLong("b"))).list();
        List<Backlog> backlogs = BACKLOGS.stream().map(q -> new Backlog(q.key(), q.label(), jdbc.sql(q.sql()).query(Long.class).single())).toList();
        return new Overview(databaseBytes(), settings.get(CAPACITY_MB) * MB, tables, backlogs);
    }

    private long databaseBytes() {
        return jdbc.sql("""
                SELECT COALESCE(SUM(COALESCE(data_length, 0) + COALESCE(index_length, 0)), 0)
                  FROM information_schema.tables WHERE table_schema = DATABASE()
                """).query(Long.class).single();
    }

    @Override
    public List<OpsAlert> check(Instant now) {
        long limit = settings.get(CAPACITY_MB) * MB;
        long used = databaseBytes();
        return capacityLevel(used, limit).map(level -> List.of(new OpsAlert("DB_CAPACITY", level, "数据库占用接近容量",
                "已用 " + used / MB + " MB / 容量 " + limit / MB + " MB（alert.db_capacity_mb）；先清理或扩容，再复核备份空间", 1)))
                .orElse(List.of());
    }

    /** 占用达容量 80% 为注意、达 100% 为严重；容量 ≤ 0 视为不检查。 */
    public static Optional<Level> capacityLevel(long used, long limit) {
        if (limit <= 0 || used * 5 < limit * 4) {
            return Optional.empty();
        }
        return Optional.of(used >= limit ? Level.CRITICAL : Level.WARN);
    }
}
