package com.liroam.ops.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;

/**
 * 基础报表：按结算时区自然日汇总。收单 = 进入履约的订单行（金额取订单行快照实付 / 成本），交码 = 首次交码的卡，退款 = 当日完成的退款，
 * 佣金 = 当日入账 + 冲正（不含提现）。毛利 = 实付 − 退款 − 成本 − 佣金净额（粗口径，不含支付手续费与税）。
 */
public class OpsReports {

    public static final int MAX_DAYS = 92;

    public record Figures(long lines, long shopPaidFen, long channelPaidFen, long cardsDelivered, long refundFen, long costFen, long commissionFen,
            long grossFen) {
    }

    public record Day(String day, long lines, long shopPaidFen, long channelPaidFen, long cardsDelivered, long refundFen, long costFen,
            long commissionFen, long grossFen) {
    }

    public record Overview(String from, String to, Figures totals, List<Day> days) {
    }

    private record DayValue(String day, long a, long b, long c) {
    }

    private final JdbcClient jdbc;
    private final ZoneId zone;

    public OpsReports(JdbcClient jdbc, ZoneId zone) {
        this.jdbc = jdbc;
        this.zone = zone;
    }

    public Overview overview(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new UserFacingException("请选择正确的起止日期");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new UserFacingException("一次最多查 " + MAX_DAYS + " 天");
        }
        LocalDateTime start = UtcColumns.toColumn(from.atStartOfDay(zone).toInstant());
        LocalDateTime end = UtcColumns.toColumn(to.plusDays(1).atStartOfDay(zone).toInstant());
        String offset = zone.getRules().getOffset(from.atStartOfDay(zone).toInstant()).getId().replace("Z", "+00:00");
        String day = "DATE_FORMAT(CONVERT_TZ(%s, '+00:00', '" + offset + "'), '%%Y-%%m-%%d')";

        Map<String, long[]> byDay = new TreeMap<>();
        collect(byDay, String.format("""
                SELECT %s AS day, COUNT(*) AS a,
                       COALESCE(SUM(CASE WHEN l.source_type = 'CHANNEL' THEN 0 ELSE s.paid_fen END), 0) AS b,
                       COALESCE(SUM(CASE WHEN l.source_type = 'CHANNEL' THEN s.paid_fen ELSE 0 END), 0) AS c
                  FROM lr_fulfillment_line l
                  LEFT JOIN lr_order_line_snapshot s ON s.source_type = l.source_type AND s.source_line_ref = l.source_line_ref
                 WHERE l.created_at >= ? AND l.created_at < ? GROUP BY day
                """, day.formatted("l.created_at")), start, end, 0, 1, 2);
        collect(byDay, String.format("""
                SELECT %s AS day, COALESCE(SUM(s.cost_cny_fen), 0) AS a, 0 AS b, 0 AS c
                  FROM lr_fulfillment_line l
                  JOIN lr_order_line_snapshot s ON s.source_type = l.source_type AND s.source_line_ref = l.source_line_ref
                 WHERE l.created_at >= ? AND l.created_at < ? GROUP BY day
                """, day.formatted("l.created_at")), start, end, 5);
        collect(byDay, String.format("""
                SELECT %s AS day, COUNT(*) AS a, 0 AS b, 0 AS c FROM (
                    SELECT MIN(delivered_at) AS delivered_at FROM lr_card_credential GROUP BY card_asset_id) first_delivery
                 WHERE delivered_at >= ? AND delivered_at < ? GROUP BY day
                """, day.formatted("delivered_at")), start, end, 3);
        collect(byDay, String.format("""
                SELECT %s AS day, COALESCE(SUM(refund_fen), 0) AS a, 0 AS b, 0 AS c FROM lr_refund_case
                 WHERE status = 'DONE' AND updated_at >= ? AND updated_at < ? GROUP BY day
                """, day.formatted("updated_at")), start, end, 4);
        collect(byDay, String.format("""
                SELECT %s AS day, COALESCE(SUM(amount_fen), 0) AS a, 0 AS b, 0 AS c FROM lr_commission_entry
                 WHERE type IN ('ACCRUE', 'REVERSE', 'ADJUST') AND created_at >= ? AND created_at < ? GROUP BY day
                """, day.formatted("created_at")), start, end, 6);

        List<Day> days = new ArrayList<>();
        long[] t = new long[7];
        byDay.forEach((d, v) -> {
            long gross = v[1] + v[2] - v[4] - v[5] - v[6];
            days.add(new Day(d, v[0], v[1], v[2], v[3], v[4], v[5], v[6], gross));
            for (int i = 0; i < 7; i++) {
                t[i] += v[i];
            }
        });
        Figures totals = new Figures(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[1] + t[2] - t[4] - t[5] - t[6]);
        return new Overview(from.toString(), to.toString(), totals, days);
    }

    /** 把查询的 a/b/c 列依次加到当天的第 {@code slots} 个指标。 */
    private void collect(Map<String, long[]> byDay, String sql, LocalDateTime start, LocalDateTime end, int... slots) {
        for (DayValue v : jdbc.sql(sql).params(start, end).query(DayValue.class).list()) {
            long[] row = byDay.computeIfAbsent(v.day(), k -> new long[7]);
            long[] values = {v.a(), v.b(), v.c()};
            for (int i = 0; i < slots.length; i++) {
                row[slots[i]] += values[i];
            }
        }
    }
}
