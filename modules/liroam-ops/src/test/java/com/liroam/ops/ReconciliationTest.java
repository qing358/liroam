package com.liroam.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.web.UserFacingException;
import com.liroam.ops.recon.PaymentReconciliation;
import com.liroam.ops.recon.ReconAlerts;
import com.liroam.ops.recon.ReconRuns;
import com.liroam.ops.recon.ReconRuns.Diff;
import com.liroam.ops.recon.ReconRuns.Run;
import com.liroam.ops.recon.ShellPayments.PaidOrder;
import com.liroam.ops.recon.SupplierReconciliation;

/**
 * 日对账（domain-design §9.3、主计划 P5）：下游 = 支付机构账单 vs 壳已支付订单（只比账单覆盖的支付方式）；
 * 上游 = 供应商账单 vs 当天成功的开卡/加包指令（按 ICCID 汇总，金额港币按快照成本版本）。差异进人工，处理留痕。
 */
class ReconciliationTest {

    private static final LocalDate DAY = LocalDate.parse("2026-11-22");
    private static OpsFixture f;
    private static PaymentReconciliation payments;
    private static SupplierReconciliation suppliers;
    private static ReconRuns runs;

    @BeforeAll
    static void start() {
        f = OpsFixture.start("ops_recon");
        payments = f.bean(PaymentReconciliation.class);
        suppliers = f.bean(SupplierReconciliation.class);
        runs = f.bean(ReconRuns.class);
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    private static List<String> kinds(Run run) {
        return runs.diffs(run.id()).stream().map(d -> d.diffKind() + ":" + (d.ourRef() != null ? d.ourRef() : d.theirRef())).sorted().toList();
    }

    @Test
    void paymentDiffsAmountMissingBothSidesAndIgnoresWallet() {
        f.payments.orders.addAll(List.of(new PaidOrder("O1", 10_000, "WECHAT", "T1"), new PaidOrder("O2", 5_000, "ALIPAY", "T2"),
                new PaidOrder("O3", 3_000, "WECHAT", "T3"), new PaidOrder("OW", 2_000, "WALLET", null)));
        Run run = payments.run(DAY, """
                order_sn,trade_no,amount
                O1,T1,100.00
                O2,T2,49
                O9,T9,10.5
                """, OpsFixture.OP);

        assertThat(f.payments.askedDay).isEqualTo(DAY);
        assertThat(run.kind()).isEqualTo("PAYMENT_DAILY");
        assertThat(run.period()).isEqualTo("2026-11-22");
        assertThat(run.status()).isEqualTo("DIFF");
        assertThat(run.matched()).isEqualTo(1);
        assertThat(run.diffCount()).isEqualTo(3);
        assertThat(kinds(run)).containsExactly("AMOUNT:O2", "MISSING_OURS:O9", "MISSING_THEIRS:O3");
        Diff amount = runs.diffs(run.id()).stream().filter(d -> d.diffKind().equals("AMOUNT")).findFirst().orElseThrow();
        assertThat(amount.ourAmountMinor()).isEqualTo(5_000);
        assertThat(amount.theirAmountMinor()).isEqualTo(4_900);
        assertThat(amount.currency()).isEqualTo("CNY");
        assertThat(f.h.count("lr_payment_statement_line WHERE run_id = ?", run.id())).isEqualTo(3);
    }

    @Test
    void balancedPaymentDayAndRerunKeepsHistory() {
        f.payments.orders.add(new PaidOrder("O1", 10_000, "WECHAT", "T1"));
        Run first = payments.run(DAY, "order_sn,trade_no,amount\nO1,T1,100\n", OpsFixture.OP);
        assertThat(first.status()).isEqualTo("BALANCED");
        assertThat(first.diffCount()).isZero();
        payments.run(DAY, "order_sn,trade_no,amount\nO1,T1,100\n", OpsFixture.OP);
        assertThat(runs.page("PAYMENT_DAILY", 1, 20).total()).isEqualTo(2);
    }

    @Test
    void badStatementIsRejectedWithLineNumberAndWritesNothing() {
        assertThatThrownBy(() -> payments.run(DAY, "sn,amount\nO1,1\n", OpsFixture.OP)).isInstanceOf(UserFacingException.class)
                .hasMessageContaining("order_sn");
        assertThatThrownBy(() -> payments.run(DAY, "order_sn,trade_no,amount\nO1,T1,abc\n", OpsFixture.OP)).isInstanceOf(UserFacingException.class)
                .hasMessageContaining("第 2 行");
        assertThatThrownBy(() -> payments.run(DAY, "order_sn,trade_no,amount\nO1,T1,1.234\n", OpsFixture.OP)).isInstanceOf(UserFacingException.class);
        assertThat(f.h.count("lr_recon_run")).isZero();
    }

    @Test
    void diffsAreResolvedOnceWithNote() {
        f.payments.orders.add(new PaidOrder("O3", 3_000, "WECHAT", "T3"));
        Run run = payments.run(DAY, "order_sn,trade_no,amount\n", OpsFixture.OP);
        long diff = runs.diffs(run.id()).get(0).id();
        assertThatThrownBy(() -> runs.resolve(diff, "RESOLVED", " ", OpsFixture.OP)).isInstanceOf(UserFacingException.class);
        runs.resolve(diff, "RESOLVED", "支付机构次日补单", OpsFixture.OP);
        assertThat(runs.diffs(run.id())).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo("RESOLVED");
            assertThat(d.resolution()).isEqualTo("支付机构次日补单");
            assertThat(d.resolver()).isEqualTo(OpsFixture.OP);
        });
        assertThatThrownBy(() -> runs.resolve(diff, "IGNORED", "x", OpsFixture.OP)).isInstanceOf(UserFacingException.class);
        assertThat(f.h.count("lr_audit_log WHERE action = 'RECON_DIFF_RESOLVE'")).isEqualTo(1);
    }

    @Test
    void openDiffsAndMissingYesterdayPaymentReconRaiseAlerts() {
        ReconAlerts alerts = f.bean(ReconAlerts.class);
        // 测试时钟 2026-11-22 12:00 东八区：昨天 = 11-21，未做
        assertThat(alerts.check(f.now())).anyMatch(a -> a.code().equals("PAYMENT_RECON_MISSING"));
        payments.run(LocalDate.parse("2026-11-21"), "order_sn,trade_no,amount\nO9,T9,1\n", OpsFixture.OP);
        assertThat(alerts.check(f.now())).noneMatch(a -> a.code().equals("PAYMENT_RECON_MISSING"))
                .anyMatch(a -> a.code().equals("RECON_DIFF_OPEN") && a.count() == 1);
    }

    // ---- 上游 ----

    private long provisionedCardWithCost(String order, long costHkdMinor) {
        String iccid = f.h.importPoolA(1, true).get(0);
        f.h.jdbc.update("INSERT INTO lr_sku_cost_version (sku_binding_id, tariff_sheet_id, fx_rate_version_id, cost_hkd_minor, cost_cny_fen) "
                + "VALUES (?, 1, 1, ?, 1)", f.h.catalog.skuANew, costHkdMinor);
        long costVersion = f.h.jdbc.queryForObject("SELECT MAX(id) FROM lr_sku_cost_version", Long.class);
        f.h.jdbc.update("""
                INSERT INTO lr_order_line_snapshot (source_type, source_line_ref, sku_binding_id, supplier_id, card_product_id, region_id,
                                                    usage_region_text, cost_version_id, unit_price_fen, paid_fen, qty)
                SELECT 'SHOP', ?, id, supplier_id, card_product_id, region_id, '测试', ?, 1, 1, 1 FROM lr_sku_binding WHERE id = ?
                """, order + "-1", costVersion, f.h.catalog.skuANew);
        long card = f.h.acceptCard(order, f.h.catalog.skuANew, "m1");
        f.h.drain();
        // 指令完成时间钉在对账日（东八区 11-22 中午）
        f.h.jdbc.update("UPDATE lr_supplier_command SET updated_at = ? WHERE card_asset_id = ?", OpsFixture.utc(f.now()), card);
        iccids.put(card, iccid);
        return card;
    }

    private final java.util.Map<Long, String> iccids = new java.util.HashMap<>();

    @Test
    void supplierDayBalancesWhenStatementMatchesProvisionCost() {
        long card = provisionedCardWithCost("O-S1", 1_250);
        Run run = suppliers.run(f.h.catalog.supplierA, DAY, "iccid,item,amount_hkd\n" + iccids.get(card) + ",开卡,12.50\n", OpsFixture.OP);
        assertThat(run.kind()).isEqualTo("SUPPLIER_DAILY");
        assertThat(run.scopeRef()).isEqualTo(Long.toString(f.h.catalog.supplierA));
        assertThat(run.status()).isEqualTo("BALANCED");
        assertThat(run.matched()).isEqualTo(1);
    }

    @Test
    void supplierDiffsAmountAndBothMissingSidesWithoutLeakingIccid() {
        long card = provisionedCardWithCost("O-S2", 1_250);
        long unbilled = provisionedCardWithCost("O-S3", 800);
        String stranger = "8985200000000009999";
        Run run = suppliers.run(f.h.catalog.supplierA, DAY,
                "iccid,item,amount_hkd\n" + iccids.get(card) + ",开卡,13.00\n" + stranger + ",开卡,5\n", OpsFixture.OP);

        assertThat(run.status()).isEqualTo("DIFF");
        List<Diff> diffs = runs.diffs(run.id());
        assertThat(diffs).extracting(Diff::diffKind).containsExactlyInAnyOrder("AMOUNT", "MISSING_THEIRS", "MISSING_OURS");
        assertThat(diffs).filteredOn(d -> d.diffKind().equals("MISSING_THEIRS")).singleElement()
                .satisfies(d -> assertThat(d.ourRef()).isEqualTo("card:" + unbilled));
        assertThat(diffs).filteredOn(d -> d.diffKind().equals("MISSING_OURS")).singleElement().satisfies(d -> {
            assertThat(d.theirRef()).isEqualTo("ICCID ****9999");
            assertThat(d.currency()).isEqualTo("HKD");
            assertThat(d.theirAmountMinor()).isEqualTo(500);
        });
        assertThat(f.h.jdbc.queryForObject("SELECT COUNT(*) FROM lr_supplier_statement_line WHERE CAST(raw_json AS CHAR) LIKE ?", Integer.class,
                "%" + stranger + "%")).isZero();
        assertThat(f.h.count("lr_supplier_statement_line WHERE iccid_hash IS NOT NULL AND run_id = ?", run.id())).isEqualTo(2);
    }

    @Test
    void supplierStatementValidation() {
        assertThatThrownBy(() -> suppliers.run(f.h.catalog.supplierA, DAY, "iccid,amount_hkd\n", OpsFixture.OP)).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> suppliers.run(f.h.catalog.supplierA, DAY, "iccid,item,amount_hkd\n12,开卡,1\n", OpsFixture.OP))
                .isInstanceOf(UserFacingException.class).hasMessageContaining("第 2 行");
        assertThatThrownBy(() -> suppliers.run(999_999, DAY, "iccid,item,amount_hkd\n", OpsFixture.OP)).isInstanceOf(UserFacingException.class);
    }
}
