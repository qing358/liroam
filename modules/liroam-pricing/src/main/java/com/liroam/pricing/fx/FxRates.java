package com.liroam.pricing.fx;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import com.liroam.common.time.UtcColumns;
import com.liroam.pricing.DisplayTime;

/** 汇率版本（{@code lr_fx_rate_version}，只有 HKD→CNY 一对）。每次同步成功一行，旧 ACTIVE 置 SUPERSEDED。 */
public class FxRates {

    static final String BASE = "HKD";
    static final String QUOTE = "CNY";

    private final JdbcClient jdbc;

    public FxRates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Stored(long id, BigDecimal rate, String sourceKey, String status, LocalDateTime fetchedAt, String sourcePublishedRaw) {
    }

    private static final String SELECT = "SELECT id, rate, source_key, status, fetched_at, source_published_raw FROM lr_fx_rate_version "
            + "WHERE base_currency = 'HKD' AND quote_currency = 'CNY'";

    public Optional<FxRateVersion> active() {
        return jdbc.sql(SELECT + " AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1").query(Stored.class).optional()
                .map(s -> new FxRateVersion(s.id(), s.rate(), s.sourceKey(), UtcColumns.fromColumn(s.fetchedAt())));
    }

    public List<FxRateRow> recent(int limit) {
        return jdbc.sql(SELECT + " ORDER BY id DESC LIMIT ?").param(Math.min(Math.max(limit, 1), 100)).query(Stored.class).list().stream()
                .map(s -> new FxRateRow(s.id(), s.rate(), s.sourceKey(), s.status(), DisplayTime.format(UtcColumns.fromColumn(s.fetchedAt())),
                        s.sourcePublishedRaw()))
                .toList();
    }

    /** 调用方负责事务。 */
    long activate(FxQuote quote, String sourceKey, Instant fetchedAt) {
        jdbc.sql("UPDATE lr_fx_rate_version SET status = 'SUPERSEDED', version = version + 1, updated_at = UTC_TIMESTAMP(3) "
                + "WHERE base_currency = ? AND quote_currency = ? AND status = 'ACTIVE'").params(BASE, QUOTE).update();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_fx_rate_version (base_currency, quote_currency, rate, source_key, source_published_at, source_published_raw, fetched_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """).params(BASE, QUOTE, quote.rate(), sourceKey, UtcColumns.toColumn(quote.publishedAt()), quote.publishedRaw(),
                UtcColumns.toColumn(fetchedAt)).update(key);
        return key.getKey().longValue();
    }
}
