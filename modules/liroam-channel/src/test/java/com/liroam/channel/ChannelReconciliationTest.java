package com.liroam.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.channel.ChannelReconciliation.ReconSummary;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.channel.testing.ChannelFixture;

/** 渠道日对账：结算单 vs 本站渠道单与履约结果 → 漏单 / 多单 / 未履约 / 金额差异，各开人工待办，渠道单上打对账标记。 */
class ChannelReconciliationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 22);
    /** 北京时间 2026-11-22 00:30，与 23:30（UTC 前一天 / 当天）都算 11-22。 */
    private static final Instant EARLY = Instant.parse("2026-11-21T16:30:00Z");
    private static final Instant LATE = Instant.parse("2026-11-22T15:30:00Z");

    private static ChannelFixture f;
    private static ChannelReconciliation recon;

    @BeforeAll
    static void start() {
        f = ChannelFixture.start("channel_recon");
        recon = f.bean(ChannelReconciliation.class);
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    @Test
    void statementIsComparedWithOurOrdersAndFulfilmentAndEveryDiffOpensATask() {
        f.h.importPoolA(2, true);
        f.adapter.addOrder("O-OK", EARLY, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.adapter.addOrder("O-AMT", LATE, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.adapter.addOrder("O-THEIRS-MISSING", LATE, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.bean(ChannelOrders.class).pullNow(f.channelId, "op");
        f.h.drain();
        f.adapter.addOrder("O-UNFULFILLED", EARLY, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.adapter.addOrder("O-NEXT-DAY", Instant.parse("2026-11-22T16:30:00Z"), "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.bean(ChannelOrders.class).pullNow(f.channelId, "op");
        f.adapter.statement(DAY,
                new ExternalStatementLine("O-OK", 4900, "{}"),
                new ExternalStatementLine("O-AMT", 3900, "{}"),
                new ExternalStatementLine("O-UNFULFILLED", 4900, "{}"),
                new ExternalStatementLine("O-OURS-MISSING", 1990, "{}"));

        ReconSummary summary = recon.run(f.channelId, DAY, "op");

        assertThat(summary.theirCount()).isEqualTo(4);
        assertThat(summary.ourCount()).isEqualTo(4);
        assertThat(summary.diffCount()).isEqualTo(4);
        assertThat(f.h.jdbc.queryForList("""
                SELECT CONCAT(kind, '|', period, '|', scope_ref, '|', status, '|', created_by, '|',
                              JSON_EXTRACT(summary_json, '$.matched'), '|', JSON_EXTRACT(summary_json, '$.diffs')) FROM lr_recon_run
                """, String.class)).as("与支付/供应商日对账同一口径，后台「日对账」页可直接列出")
                .containsExactly("CHANNEL_DAILY|2026-11-22|CH_A|DIFF|op|1|4");
        assertThat(f.h.jdbc.queryForObject("SELECT CAST(summary_json AS CHAR) FROM lr_recon_run", String.class)).contains("\"channelCode\": \"CH_A\"");
        assertThat(f.h.jdbc.queryForList("""
                SELECT CONCAT(diff_kind, '|', COALESCE(our_ref, '-'), '|', COALESCE(their_ref, '-'), '|', currency, '|',
                              COALESCE(our_amount_minor, '-'), '|', COALESCE(their_amount_minor, '-'), '|', status)
                  FROM lr_recon_diff ORDER BY diff_kind, id
                """, String.class)).containsExactly(
                "AMOUNT|O-AMT|O-AMT|CNY|4900|3900|OPEN",
                "MISSING_OURS|-|O-OURS-MISSING|CNY|-|1990|OPEN",
                "MISSING_THEIRS|O-THEIRS-MISSING|-|CNY|4900|-|OPEN",
                "STATUS|O-UNFULFILLED|O-UNFULFILLED|CNY|4900|4900|OPEN");
        assertThat(f.h.count("lr_manual_task WHERE kind = 'RECON_DIFF' AND ref_type = 'RECON_DIFF' AND status = 'OPEN'")).isEqualTo(4);
        assertThat(f.h.jdbc.queryForList("SELECT CONCAT(external_order_id, '|', COALESCE(recon_status, '-')) FROM lr_channel_order ORDER BY id",
                String.class)).containsExactly("O-OK|BALANCED", "O-AMT|DIFF", "O-THEIRS-MISSING|DIFF", "O-UNFULFILLED|DIFF", "O-NEXT-DAY|-");
    }

    @Test
    void rerunAfterFixingUpdatesTheMarkerFromTheLatestRun() {
        f.h.importPoolA(1, true);
        f.adapter.addOrder("O-1", EARLY, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.bean(ChannelOrders.class).pullNow(f.channelId, "op");
        f.adapter.statement(DAY, new ExternalStatementLine("O-1", 4900, "{}"));
        recon.run(f.channelId, DAY, "op");
        assertThat(f.h.jdbc.queryForObject("SELECT recon_status FROM lr_channel_order", String.class)).as("还没履约").isEqualTo("DIFF");

        f.h.drain();
        ReconSummary second = recon.run(f.channelId, DAY, "op");

        assertThat(second.diffCount()).isZero();
        assertThat(f.h.jdbc.queryForObject("SELECT CONCAT(recon_status, '|', recon_run_id) FROM lr_channel_order", String.class))
                .isEqualTo("BALANCED|" + second.runId());
        assertThat(f.h.count("lr_recon_run")).isEqualTo(2);
        assertThat(f.h.jdbc.queryForObject("SELECT status FROM lr_recon_run WHERE id = ?", String.class, second.runId())).isEqualTo("BALANCED");
    }

    @Test
    void dailyRunCoversYesterdayOnceForEveryEnabledChannel() {
        f.h.clock.set(Instant.parse("2026-11-23T02:00:00Z"));

        assertThat(recon.runDueDaily()).isEqualTo(1);
        assertThat(recon.runDueDaily()).as("同一天只跑一次").isZero();
        assertThat(f.h.jdbc.queryForObject("SELECT period FROM lr_recon_run", String.class)).isEqualTo("2026-11-22");
    }
}
