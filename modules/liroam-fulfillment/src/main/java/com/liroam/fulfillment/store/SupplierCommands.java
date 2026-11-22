package com.liroam.fulfillment.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.state.CommandPriority;
import com.liroam.fulfillment.state.CommandStatus;
import com.liroam.fulfillment.state.CommandType;

/**
 * 供应商指令表（domain-design §6.3）。{@code request_no = {type}-{card}-{plan|0}-{序号}} 确定性生成；
 * 同一张卡同一动作已有未结束的指令时直接返回它（重入不重复下发）。
 */
public class SupplierCommands {

    public record Command(
            long id,
            String requestNo,
            long supplierId,
            Long cardAssetId,
            Long cardPlanId,
            CommandType type,
            int priority,
            CommandStatus status,
            int attempts,
            int maxAttempts,
            LocalDateTime nextAttemptAt,
            String errorClass,
            String errorCode,
            String errorMsg,
            String upstreamRef,
            String lockedBy,
            LocalDateTime lockedUntil,
            LocalDateTime updatedAt) {
    }

    private static final String SELECT = """
            SELECT id, request_no, supplier_id, card_asset_id, card_plan_id, type, priority, status, attempts, max_attempts, next_attempt_at,
                   error_class, error_code, error_msg, upstream_ref, locked_by, locked_until, updated_at
              FROM lr_supplier_command
            """;
    private static final String OPEN = "('PENDING', 'RUNNING', 'LOOKUP_PENDING', 'MANUAL')";

    private final JdbcClient jdbc;

    public SupplierCommands(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Command> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Command.class).optional();
    }

    public List<Command> byCard(long cardAssetId) {
        return jdbc.sql(SELECT + " WHERE card_asset_id = ? ORDER BY id").param(cardAssetId).query(Command.class).list();
    }

    public Optional<Command> open(CommandType type, long cardAssetId, Long cardPlanId) {
        return jdbc.sql(SELECT + " WHERE type = ? AND card_asset_id = ? AND card_plan_id <=> ? AND status IN " + OPEN + " ORDER BY id DESC LIMIT 1")
                .params(type.name(), cardAssetId, cardPlanId).query(Command.class).optional();
    }

    /** 已有同卡同动作未结束的指令则返回它；按需刷新可把排队中的轮询提到更高优先级。 */
    public Command enqueue(CommandType type, long supplierId, long cardAssetId, Long cardPlanId, CommandPriority priority, int maxAttempts, Instant now) {
        Optional<Command> existing = open(type, cardAssetId, cardPlanId);
        if (existing.isPresent()) {
            if (priority.value() < existing.get().priority()) {
                jdbc.sql("UPDATE lr_supplier_command SET priority = ? WHERE id = ?").params(priority.value(), existing.get().id()).update();
            }
            return existing.get();
        }
        for (int retry = 0; ; retry++) {
            int seq = jdbc.sql("SELECT COUNT(*) FROM lr_supplier_command WHERE type = ? AND card_asset_id = ? AND card_plan_id <=> ?")
                    .params(type.name(), cardAssetId, cardPlanId).query(Integer.class).single() + 1;
            String requestNo = type.name() + "-" + cardAssetId + "-" + (cardPlanId == null ? 0 : cardPlanId) + "-" + seq;
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            try {
                jdbc.sql("""
                        INSERT INTO lr_supplier_command (request_no, supplier_id, card_asset_id, card_plan_id, type, priority, status, max_attempts,
                                                         next_attempt_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                        """).params(requestNo, supplierId, cardAssetId, cardPlanId, type.name(), priority.value(), maxAttempts,
                        UtcColumns.toColumn(now)).update(keys);
                return find(keys.getKey().longValue()).orElseThrow();
            } catch (DuplicateKeyException e) {
                if (retry >= 3) {
                    throw e;
                }
            }
        }
    }

    /**
     * 事务内认领：到期的 PENDING / LOOKUP_PENDING，以及持有者租约过期（崩溃）的 RUNNING。
     * 写 {@code locked_by/locked_until}，提交后其他实例看不到它，直到租约过期。
     */
    public List<Command> claim(int batch, String workerId, Instant now, Instant lockedUntil) {
        LocalDateTime at = UtcColumns.toColumn(now);
        List<Long> ids = jdbc.sql("""
                SELECT id FROM lr_supplier_command
                 WHERE (status IN ('PENDING', 'LOOKUP_PENDING') AND next_attempt_at <= ? AND (locked_until IS NULL OR locked_until < ?))
                    OR (status = 'RUNNING' AND locked_until < ?)
                 ORDER BY priority, next_attempt_at, id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """).params(at, at, at, batch).query(Long.class).list();
        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.sql("""
                UPDATE lr_supplier_command
                   SET status = IF(status = 'PENDING', 'RUNNING', status), locked_by = :worker, locked_until = :until,
                       version = version + 1, updated_at = :now
                 WHERE id IN (:ids)
                """).param("worker", workerId).param("until", UtcColumns.toColumn(lockedUntil)).param("now", at).param("ids", ids).update();
        return jdbc.sql(SELECT + " WHERE id IN (:ids) ORDER BY priority, next_attempt_at, id").param("ids", ids).query(Command.class).list();
    }

    /** 结果落库的前提：仍由本 worker 持有（租约没被他人回收）。 */
    public boolean stillHeld(long id, String workerId) {
        return jdbc.sql("SELECT COUNT(*) FROM lr_supplier_command WHERE id = ? AND locked_by = ? FOR UPDATE").params(id, workerId)
                .query(Integer.class).single() == 1;
    }

    public void succeed(Command c, String upstreamRef, Instant now) {
        finish(c, CommandStatus.SUCCEEDED, c.attempts() + 1, null, null, null, upstreamRef, now, now);
    }

    /** 回到 PENDING 等下一次；{@code countAttempt=false} 用于限流、池空、冻结这类不算失败的推迟。 */
    public void retryLater(Command c, boolean countAttempt, String errorClass, String errorCode, String errorMsg, Instant next, Instant now) {
        finish(c, CommandStatus.PENDING, countAttempt ? c.attempts() + 1 : c.attempts(), errorClass, errorCode, errorMsg, null, next, now);
    }

    public void lookupPending(Command c, String errorMsg, Instant next, Instant now) {
        finish(c, CommandStatus.LOOKUP_PENDING, c.attempts() + 1, "UNKNOWN", null, errorMsg, null, next, now);
    }

    public void manual(Command c, boolean countAttempt, String errorClass, String errorCode, String errorMsg, Instant now) {
        finish(c, CommandStatus.MANUAL, countAttempt ? c.attempts() + 1 : c.attempts(), errorClass, errorCode, errorMsg, null, now, now);
    }

    private void finish(Command c, CommandStatus to, int attempts, String errorClass, String errorCode, String errorMsg, String upstreamRef,
            Instant next, Instant now) {
        if (c.status() != to) {
            CommandStatus.MACHINE.requireMove(c.status(), to);
        }
        jdbc.sql("""
                UPDATE lr_supplier_command
                   SET status = ?, attempts = ?, error_class = ?, error_code = ?, error_msg = ?, upstream_ref = COALESCE(?, upstream_ref),
                       next_attempt_at = ?, locked_by = NULL, locked_until = NULL, version = version + 1, updated_at = ?
                 WHERE id = ?
                """).params(to.name(), attempts, errorClass, Texts.clip(errorCode, 64), Texts.clip(errorMsg, 512), upstreamRef,
                UtcColumns.toColumn(next), UtcColumns.toColumn(now), c.id()).update();
    }

    /** 取消未在执行中的指令（退款取消未执行动作）；执行中的交给 worker 收尾。 */
    public int cancelOpen(long cardAssetId, Long cardPlanId, Instant now) {
        LocalDateTime at = UtcColumns.toColumn(now);
        return jdbc.sql("""
                UPDATE lr_supplier_command SET status = 'CANCELLED', locked_by = NULL, locked_until = NULL, version = version + 1, updated_at = ?
                 WHERE card_asset_id = ? AND (? IS NULL OR card_plan_id = ?) AND type <> 'QUERY'
                   AND (status IN ('PENDING', 'MANUAL') OR (status = 'LOOKUP_PENDING' AND (locked_until IS NULL OR locked_until < ?)))
                """).params(at, cardAssetId, cardPlanId, cardPlanId, at).update();
    }
}
