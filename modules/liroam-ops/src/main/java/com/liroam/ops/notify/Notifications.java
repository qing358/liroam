package com.liroam.ops.notify;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.DisplayTime;

/** 会员站内通知（「我的卡」顶部提示）。 */
public class Notifications {

    public record Item(long id, String kind, Long cardAssetId, String title, String body, boolean read, String createdAt) {
    }

    public record Inbox(long unread, List<Item> items) {
    }

    private record Row(long id, String kind, Long cardAssetId, String title, String body, LocalDateTime readAt, LocalDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public Notifications(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Inbox mine(String memberId) {
        long unread = jdbc.sql("SELECT COUNT(*) FROM lr_notification WHERE member_id = ? AND read_at IS NULL").param(memberId).query(Long.class).single();
        List<Item> items = jdbc.sql("""
                SELECT id, kind, card_asset_id, title, body, read_at, created_at FROM lr_notification
                 WHERE member_id = ? ORDER BY id DESC LIMIT 20
                """).param(memberId).query(Row.class).list().stream()
                .map(r -> new Item(r.id(), r.kind(), r.cardAssetId(), r.title(), r.body(), r.readAt() != null,
                        DisplayTime.format(UtcColumns.fromColumn(r.createdAt()))))
                .toList();
        return new Inbox(unread, items);
    }

    public void markAllRead(String memberId) {
        jdbc.sql("UPDATE lr_notification SET read_at = ? WHERE member_id = ? AND read_at IS NULL")
                .params(UtcColumns.toColumn(clock.instant()), memberId).update();
    }
}
