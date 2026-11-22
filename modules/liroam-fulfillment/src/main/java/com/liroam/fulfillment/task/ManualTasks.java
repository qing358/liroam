package com.liroam.fulfillment.task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;

/** 统一人工队列（{@code lr_manual_task}）。同一对象同一类问题只开一条 OPEN 待办，避免刷屏。 */
public class ManualTasks {

    public enum Kind {
        /** 指令 Rejected / 超过最大尝试 / 查档仍未知。 */
        COMMAND_FAILED,
        /** 履约行拆卡反复失败。 */
        EXPAND_FAILED,
        /** 卡池无可分配卡。 */
        POOL_EMPTY,
        /** 开通后超时未交码。 */
        CREDENTIAL_TIMEOUT,
        /** 卡动作降级为本地标记，需与供应商账单核对。 */
        RECON_DIFF,
        /** 退款流程中需人工处理（如未开始的加包无法撤销）。 */
        REFUND_EXCEPTION
    }

    public enum Status { OPEN, DONE, IGNORED }

    public record Task(long id, Kind kind, String refType, String refId, Status status, String note, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 后台列表行。 */
    public record TaskView(long id, String kind, String refType, String refId, String status, String note, String createdText, String updatedText) {
    }

    private static final String SELECT = "SELECT id, kind, ref_type, ref_id, status, note, created_at, updated_at FROM lr_manual_task";

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public ManualTasks(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** @return 新开或已存在的 OPEN 待办 id */
    public long open(Kind kind, String refType, Object refId, String note, Instant now) {
        Optional<Long> existing = jdbc.sql("SELECT id FROM lr_manual_task WHERE kind = ? AND ref_type = ? AND ref_id = ? AND status = 'OPEN' LIMIT 1")
                .params(kind.name(), refType, String.valueOf(refId)).query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        LocalDateTime at = UtcColumns.toColumn(now);
        jdbc.sql("INSERT INTO lr_manual_task (kind, ref_type, ref_id, status, note, created_at, updated_at) VALUES (?, ?, ?, 'OPEN', ?, ?, ?)")
                .params(kind.name(), refType, String.valueOf(refId), note, at, at).update(keys);
        return keys.getKey().longValue();
    }

    /** 同一对象同一类问题是否开过待办（不论是否已处理），用于只提醒一次的场景。 */
    public boolean everOpened(Kind kind, String refType, Object refId) {
        return jdbc.sql("SELECT COUNT(*) FROM lr_manual_task WHERE kind = ? AND ref_type = ? AND ref_id = ?")
                .params(kind.name(), refType, String.valueOf(refId)).query(Integer.class).single() > 0;
    }

    public List<Task> list(Status status) {
        String where = status == null ? "" : " WHERE status = '" + status.name() + "'";
        return jdbc.sql(SELECT + where + " ORDER BY id DESC LIMIT 500").query(Task.class).list();
    }

    public List<TaskView> views(Status status) {
        return list(status).stream().map(t -> new TaskView(t.id(), t.kind().name(), t.refType(), t.refId(), t.status().name(), t.note(),
                DisplayTime.format(UtcColumns.fromColumn(t.createdAt())), DisplayTime.format(UtcColumns.fromColumn(t.updatedAt())))).toList();
    }

    public void resolve(long id, String note, String actor) {
        Task task = jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Task.class).optional()
                .orElseThrow(() -> new UserFacingException("找不到这条待办"));
        if (task.status() != Status.OPEN) {
            throw new UserFacingException("这条待办已处理");
        }
        jdbc.sql("""
                UPDATE lr_manual_task SET status = 'DONE', assignee = ?, note = CONCAT(COALESCE(note, ''), IF(note IS NULL OR note = '', '', '\n'), ?),
                       version = version + 1, updated_at = UTC_TIMESTAMP(3)
                 WHERE id = ? AND status = 'OPEN'
                """).params(actor, note == null ? "" : note, id).update();
        audit.record(actor, "MANUAL_TASK_RESOLVE", "MANUAL_TASK", Long.toString(id), null, null);
    }
}
