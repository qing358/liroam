package com.liroam.ops.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.DisplayTime;

/**
 * 卡到期提醒：对有属主、在用（已交码 / 使用中 / 停机）且将在最大档位内到期的卡，取「剩余时长 ≤ 档位」中最小的一档发一次；
 * 已发过更小档位的不再补发大档。去重键含到期时间，续期后重新计。站内通知必发，短信按 {@link NotifySettings#EXPIRY_SMS_ENABLED}。
 */
public class ExpiryReminders {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReminders.class);

    private record Due(long id, String ownerMemberId, String iccidLast4, LocalDateTime expiresAt) {
    }

    private final JdbcClient jdbc;
    private final Settings settings;
    private final Optional<MemberSms> sms;
    private final Clock clock;

    public ExpiryReminders(JdbcClient jdbc, Settings settings, Optional<MemberSms> sms, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.sms = sms;
        this.clock = clock;
    }

    /** @return 本轮新发的提醒数 */
    public int runOnce() {
        List<Integer> hours = thresholds(settings.get(NotifySettings.EXPIRY_REMIND_BEFORE));
        if (hours.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        List<Due> due = jdbc.sql("""
                SELECT id, owner_member_id, iccid_last4, expires_at FROM lr_card_asset
                 WHERE owner_member_id IS NOT NULL AND status IN ('DELIVERED', 'IN_USE', 'SUSPENDED')
                   AND expires_at > ? AND expires_at <= ?
                 ORDER BY expires_at
                 LIMIT 500
                """).params(UtcColumns.toColumn(now), UtcColumns.toColumn(now.plus(Duration.ofHours(hours.get(hours.size() - 1)))))
                .query(Due.class).list();
        boolean smsOn = settings.get(NotifySettings.EXPIRY_SMS_ENABLED) && sms.isPresent();
        int sent = 0;
        for (Due d : due) {
            Instant expires = UtcColumns.fromColumn(d.expiresAt());
            long remainingMinutes = Duration.between(now, expires).toMinutes();
            int threshold = hours.stream().filter(h -> remainingMinutes <= h * 60L).findFirst().orElseThrow();
            String prefix = "EXPIRY:" + d.id() + ":" + expires.toEpochMilli() + ":";
            long smallestSent = jdbc.sql("""
                    SELECT COALESCE(MIN(CAST(SUBSTRING_INDEX(dedupe_key, ':', -1) AS UNSIGNED)), 0) FROM lr_notification WHERE dedupe_key LIKE ?
                    """).param(prefix + "%").query(Long.class).single();
            if (smallestSent > 0 && smallestSent <= threshold) {
                continue;
            }
            String text = "你的旅行卡（尾号 " + (d.iccidLast4() == null ? "—" : d.iccidLast4()) + "）将于 " + DisplayTime.format(expires)
                    + " 到期，可在「我的卡」加购流量包继续使用。";
            try {
                jdbc.sql("""
                        INSERT INTO lr_notification (member_id, kind, card_asset_id, dedupe_key, title, body, created_at)
                        VALUES (?, 'EXPIRY_REMIND', ?, ?, '旅行卡即将到期', ?, ?)
                        """).params(d.ownerMemberId(), d.id(), prefix + threshold, text, UtcColumns.toColumn(now)).update();
            } catch (DuplicateKeyException concurrent) {
                continue;
            }
            sent++;
            if (smsOn) {
                String status = trySms(d.ownerMemberId(), text) ? "SENT" : "FAILED";
                jdbc.sql("UPDATE lr_notification SET sms_status = ? WHERE dedupe_key = ?").params(status, prefix + threshold).update();
            }
        }
        return sent;
    }

    private boolean trySms(String memberId, String text) {
        try {
            return sms.get().send(memberId, text);
        } catch (RuntimeException e) {
            log.warn("到期提醒短信发送失败：会员 {}", memberId, e);
            return false;
        }
    }

    /** "72h,24h" → [24, 72]（升序）；不认识的段忽略。 */
    static List<Integer> thresholds(String raw) {
        List<Integer> hours = new ArrayList<>();
        for (String part : raw.split(",")) {
            String p = part.trim().toLowerCase();
            if (p.matches("\\d{1,4}h?")) {
                int h = Integer.parseInt(p.replace("h", ""));
                if (h > 0 && !hours.contains(h)) {
                    hours.add(h);
                }
            }
        }
        hours.sort(Comparator.naturalOrder());
        return hours;
    }
}
