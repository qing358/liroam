package com.liroam.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.common.time.UtcColumns;
import com.liroam.schema.testing.ItSchema;

/** 壳的 JDBC 会话时区是 Asia/Shanghai；自有 DATETIME(3) 列经 UtcColumns 写入后库里存的必须是 UTC 墙钟。 */
class UtcColumnsRoundTripTest {

    @Test
    void storesUtcWallClockAndReadsBackTheSameInstant() {
        JdbcTemplate jdbc = new JdbcTemplate(ItSchema.migrated("fulfillment_utc"));
        Instant expiresAt = Instant.parse("2026-12-31T15:59:59.123Z");

        jdbc.update("INSERT INTO lr_card_asset (fulfillment_line_id, unit_seq, supplier_id, card_product_id, region_id, form, expires_at) "
                + "VALUES (1, 1, 1, 1, 1, 'ESIM', ?)", UtcColumns.toColumn(expiresAt));

        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(expires_at, '%Y-%m-%d %H:%i:%s.%f') FROM lr_card_asset", String.class))
                .isEqualTo("2026-12-31 15:59:59.123000");
        assertThat(UtcColumns.fromColumn(jdbc.queryForObject("SELECT expires_at FROM lr_card_asset", LocalDateTime.class))).isEqualTo(expiresAt);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, created_at, UTC_TIMESTAMP(3)) BETWEEN -5 AND 60 FROM lr_card_asset", Boolean.class))
                .as("created_at 默认值是 UTC").isTrue();
    }
}
