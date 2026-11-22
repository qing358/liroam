package com.liroam.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.web.UserFacingException;
import com.liroam.ops.report.OpsReports;
import com.liroam.ops.report.OpsReports.Overview;

/** 基础报表（主计划 P5）：按东八区自然日汇总收单、交码、退款、成本、佣金与毛利；自营 / 渠道分开计。 */
class OpsReportsTest {

    private static OpsFixture f;
    private static OpsReports reports;

    @BeforeAll
    static void start() {
        f = OpsFixture.start("ops_reports");
        reports = f.bean(OpsReports.class);
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    private long line(String order, long paidFen, long costFen) {
        f.h.jdbc.update("""
                INSERT INTO lr_order_line_snapshot (source_type, source_line_ref, sku_binding_id, supplier_id, card_product_id, region_id,
                                                    usage_region_text, unit_price_fen, paid_fen, cost_cny_fen, qty)
                SELECT 'SHOP', ?, id, supplier_id, card_product_id, region_id, '测试', ?, ?, ?, 1 FROM lr_sku_binding WHERE id = ?
                """, order + "-1", paidFen, paidFen, costFen, f.h.catalog.skuANew);
        long card = f.deliveredCard(order, "m1");
        long line = f.h.jdbc.queryForObject("SELECT fulfillment_line_id FROM lr_card_asset WHERE id = ?", Long.class, card);
        f.h.jdbc.update("UPDATE lr_fulfillment_line SET created_at = ? WHERE id = ?", OpsFixture.utc(f.now()), line);
        f.h.jdbc.update("UPDATE lr_card_credential SET delivered_at = ? WHERE card_asset_id = ?", OpsFixture.utc(f.now()), card);
        return card;
    }

    @Test
    void dayTotalsAndGrossMargin() {
        long card = line("O-R1", 10_000, 6_000);
        line("O-R2", 5_000, 3_000);
        f.h.jdbc.update("""
                INSERT INTO lr_refund_case (source, source_ref, target_type, card_asset_id, card_state_at_apply, decision, rule_version, status,
                                            refund_fen, updated_at)
                VALUES ('DIRECT', 'R-1', 'CARD', ?, 'DELIVERED', 'AUTO_APPROVE', 1, 'DONE', 5000, ?)
                """, card, OpsFixture.utc(f.now()));
        f.h.jdbc.update("""
                INSERT INTO lr_commission_entry (agent_ref, level, source_type, source_line_ref, policy_id, policy_version, type, amount_fen, status, created_at)
                VALUES ('D1', 1, 'SHOP', 'O-R1-1', 1, 1, 'ACCRUE', 800, 'PENDING', ?), ('D1', 1, 'SHOP', 'O-R1-1', 1, 1, 'REVERSE', -400, 'PENDING', ?),
                       ('D1', 1, 'WITHDRAWAL', 'W:1', 0, 0, 'WITHDRAW', -300, 'SETTLED', ?)
                """, OpsFixture.utc(f.now()), OpsFixture.utc(f.now()), OpsFixture.utc(f.now()));

        LocalDate day = LocalDate.parse("2026-11-22");
        Overview o = reports.overview(day, day);
        assertThat(o.totals().lines()).isEqualTo(2);
        assertThat(o.totals().shopPaidFen()).isEqualTo(15_000);
        assertThat(o.totals().channelPaidFen()).isZero();
        assertThat(o.totals().cardsDelivered()).isEqualTo(2);
        assertThat(o.totals().refundFen()).isEqualTo(5_000);
        assertThat(o.totals().costFen()).isEqualTo(9_000);
        assertThat(o.totals().commissionFen()).isEqualTo(400);
        assertThat(o.totals().grossFen()).isEqualTo(15_000 - 5_000 - 9_000 - 400);
        assertThat(o.days()).singleElement().satisfies(d -> {
            assertThat(d.day()).isEqualTo("2026-11-22");
            assertThat(d.shopPaidFen()).isEqualTo(15_000);
        });

        assertThat(reports.overview(day.plusDays(1), day.plusDays(1)).totals().lines()).isZero();
    }

    @Test
    void shanghaiDayBoundary() {
        line("O-R3", 1_000, 0);
        // 2026-11-22 16:30 UTC = 11-23 00:30 东八区
        f.h.jdbc.update("UPDATE lr_fulfillment_line SET created_at = ?", OpsFixture.utc(f.now().plus(Duration.ofMinutes(12 * 60 + 30))));
        assertThat(reports.overview(LocalDate.parse("2026-11-22"), LocalDate.parse("2026-11-22")).totals().lines()).isZero();
        assertThat(reports.overview(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-23")).totals().lines()).isEqualTo(1);
    }

    @Test
    void rangeIsBounded() {
        assertThatThrownBy(() -> reports.overview(LocalDate.parse("2026-11-22"), LocalDate.parse("2026-11-21"))).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> reports.overview(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-11-21"))).isInstanceOf(UserFacingException.class)
                .hasMessageContaining("92");
    }
}
