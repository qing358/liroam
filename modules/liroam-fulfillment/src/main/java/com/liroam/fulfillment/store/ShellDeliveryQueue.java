package com.liroam.fulfillment.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.time.UtcColumns;

/** 壳订单发货回写待办（{@code lr_shell_delivery}，H5）；每张订单一行，幂等。 */
public class ShellDeliveryQueue {

    public record Pending(long id, String liOrderSn, int attempts) {
    }

    private final JdbcClient jdbc;

    public ShellDeliveryQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(String liOrderSn, Instant now) {
        LocalDateTime at = UtcColumns.toColumn(now);
        jdbc.sql("INSERT IGNORE INTO lr_shell_delivery (li_order_sn, status, next_attempt_at, created_at, updated_at) VALUES (?, 'PENDING', ?, ?, ?)")
                .params(liOrderSn, at, at, at).update();
    }

    /** 事务内认领到期待办并加租约，提交后在事务外调壳。 */
    public List<Pending> claim(int batch, Instant now, Instant lockedUntil) {
        LocalDateTime at = UtcColumns.toColumn(now);
        List<Pending> due = jdbc.sql("""
                SELECT id, li_order_sn, attempts FROM lr_shell_delivery
                 WHERE status = 'PENDING' AND next_attempt_at <= ? AND (locked_until IS NULL OR locked_until < ?)
                 ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                """).params(at, at, batch).query(Pending.class).list();
        if (!due.isEmpty()) {
            jdbc.sql("UPDATE lr_shell_delivery SET locked_until = :until, updated_at = :now WHERE id IN (:ids)")
                    .param("until", UtcColumns.toColumn(lockedUntil)).param("now", at).param("ids", due.stream().map(Pending::id).toList()).update();
        }
        return due;
    }

    public void done(long id, Instant now) {
        jdbc.sql("UPDATE lr_shell_delivery SET status = 'DONE', attempts = attempts + 1, last_error = NULL, locked_until = NULL, "
                + "version = version + 1, updated_at = ? WHERE id = ?").params(UtcColumns.toColumn(now), id).update();
    }

    public void failed(long id, String error, Instant next, Instant now) {
        jdbc.sql("UPDATE lr_shell_delivery SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?, locked_until = NULL, "
                + "version = version + 1, updated_at = ? WHERE id = ?")
                .params(Texts.clip(error, 512), UtcColumns.toColumn(next), UtcColumns.toColumn(now), id).update();
    }
}
