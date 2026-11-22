package com.liroam.commission;

import static com.liroam.commission.CommissionFixture.A1;
import static com.liroam.commission.CommissionFixture.A2;
import static com.liroam.commission.CommissionFixture.OP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.commission.AgentDirectory.AgentChain;
import com.liroam.commission.CommissionPolicies.FloorPriceInput;
import com.liroam.commission.CommissionPolicies.PolicyInput;
import com.liroam.common.web.UserFacingException;
import com.liroam.refund.RefundCaseView;

/**
 * 佣金引擎与账本（domain-design §10、§9.3）：策略只改配置即切换（CT-COM-01）、作用域优先级、入账幂等、T+N 结算、
 * 退款三情形联动卡状态与冲正（CT-REF-01 佣金部分）、部分退款比例与末笔轧差、已提现后冲正形成负余额。
 */
class CommissionLedgerTest {

    private static CommissionFixture f;
    private static final AgentChain CHAIN = new AgentChain(A1, A2);

    @BeforeAll
    static void start() {
        f = CommissionFixture.start("commission_ledger");
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
    void paidRateSplitsPoolBetweenTwoLevelsAndWaitsForSettleWindow() {
        f.paidRate("0.10", "0.8", "0.2", 1);
        f.deliveredCard("O-1", 10_000, CHAIN);

        assertThat(f.accruals.runOnce(50)).isEqualTo(1);

        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(800L);
        assertThat(f.amounts("D2", "ACCRUE")).containsExactly(200L);
        assertThat(f.statuses("D1", "ACCRUE")).containsExactly("PENDING");
        // 交码在 2026-11-22 12:00（东八区），T+1 = 2026-11-23 00:00 东八区
        Instant settleAt = f.h.jdbc.queryForObject("SELECT settleable_at FROM lr_commission_entry WHERE agent_ref = 'D1'", java.time.LocalDateTime.class)
                .toInstant(java.time.ZoneOffset.UTC);
        assertThat(settleAt).isEqualTo(Instant.parse("2026-11-22T16:00:00Z"));
        assertThat(f.ledger.balance("D1").pendingFen()).isEqualTo(800);
        assertThat(f.ledger.balance("D1").settleableFen()).isZero();

        f.ledger.settleDue();
        assertThat(f.statuses("D1", "ACCRUE")).containsExactly("PENDING");

        f.advance(Duration.ofHours(12));
        assertThat(f.ledger.settleDue()).isEqualTo(2);
        assertThat(f.statuses("D1", "ACCRUE")).containsExactly("SETTLEABLE");
        assertThat(f.ledger.balance("D1").settleableFen()).isEqualTo(800);
        assertThat(f.ledger.balance("D1").pendingFen()).isZero();
    }

    @Test
    void accrualRunsOncePerLineAndOnlyAfterDelivery() {
        f.paidRate("0.10", "0.8", "0.2", 1);
        f.h.importPoolA(1, false);
        f.shopLine("O-2", f.c.skuANew, 1, 10_000, CHAIN);
        f.h.drain();

        assertThat(f.accruals.runOnce(50)).isZero();

        f.deliveredCard("O-3", 10_000, CHAIN);
        assertThat(f.accruals.runOnce(50)).isEqualTo(1);
        assertThat(f.accruals.runOnce(50)).isZero();
        assertThat(f.amounts("D1", "ACCRUE")).hasSize(1);
    }

    @Test
    void lineWithoutAgentOrPolicyIsMarkedAndNotRescanned() {
        f.deliveredCard("O-NOPOLICY", 10_000, CHAIN);
        f.deliveredCard("O-NOAGENT", 10_000, null);

        assertThat(f.accruals.runOnce(50)).isEqualTo(2);
        assertThat(f.h.jdbc.queryForList("SELECT outcome FROM lr_commission_accrual ORDER BY source_line_ref", String.class))
                .containsExactly("NO_AGENT", "NO_POLICY");
        assertThat(f.h.count("lr_commission_entry")).isZero();

        f.paidRate("0.10", "1", "0", 1);
        assertThat(f.accruals.runOnce(50)).isZero();
    }

    @Test
    void lineDeliveredBeforePolicyTakesEffectIsNotBackfilled() {
        long before = f.deliveredCard("O-BEFORE", 10_000, CHAIN);
        pinDelivery(before, f.now());
        f.advance(Duration.ofHours(1));
        f.paidRate("0.10", "0.8", "0.2", 1);

        assertThat(f.accruals.runOnce(50)).isEqualTo(1);
        assertThat(f.h.jdbc.queryForObject("SELECT outcome FROM lr_commission_accrual", String.class)).isEqualTo("NO_POLICY");
        assertThat(f.h.count("lr_commission_entry")).isZero();

        long after = f.deliveredCard("O-AFTER", 10_000, CHAIN);
        pinDelivery(after, f.now());
        assertThat(f.accruals.runOnce(50)).isEqualTo(1);
        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(800L);
    }

    @Test
    void versionInForceAtDeliveryAppliesEvenIfChangedBeforeScan() {
        f.paidRate("0.10", "0.8", "0.2", 1);
        long card = f.deliveredCard("O-V1", 10_000, CHAIN);
        pinDelivery(card, f.now());
        f.advance(Duration.ofHours(1));
        f.paidRate("0.20", "0.8", "0.2", 1);

        f.accruals.runOnce(50);
        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(800L);
        assertThat(f.h.jdbc.queryForList("SELECT DISTINCT policy_version FROM lr_commission_entry", Integer.class)).containsExactly(1);
    }

    /** 交码时间取库默认（真实时钟），钉到测试时钟。 */
    private static void pinDelivery(long card, Instant at) {
        f.h.jdbc.update("UPDATE lr_card_credential SET delivered_at = ? WHERE card_asset_id = ?",
                java.time.LocalDateTime.ofInstant(at, java.time.ZoneOffset.UTC), card);
    }

    @Test
    void inviterIsOptional() {
        f.paidRate("0.10", "0.8", "0.2", 1);
        f.deliveredCard("O-SOLO", 10_000, new AgentChain(A1, null));
        f.accruals.runOnce(50);
        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(800L);
        assertThat(f.amounts("D2", "ACCRUE")).isEmpty();
    }

    @Test
    void switchingToFloorDiffIsConfigurationOnly() {
        f.paidRate("0.10", "0.8", "0.2", 1);
        f.deliveredCard("O-PAID", 10_000, CHAIN);
        f.accruals.runOnce(50);

        CommissionPolicy floor = f.policies.save(new PolicyInput("GLOBAL_DEFAULT", "FLOOR_DIFF", "GLOBAL", null, null, new BigDecimal("0.8"),
                new BigDecimal("0.2"), false, 1, "DELIVERED", true, null), OP);
        assertThat(floor.version()).isEqualTo(2);
        CommissionPolicy withFloor = f.policies.saveFloorPrice(floor.code(), new FloorPriceInput("1", null, f.c.skuANew, 7_000L), OP);
        assertThat(withFloor.version()).isEqualTo(3);

        f.deliveredCard("O-FLOOR", 10_000, CHAIN);
        f.accruals.runOnce(50);

        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(800L, 2_400L);
        assertThat(f.amounts("D2", "ACCRUE")).containsExactly(200L, 600L);
        assertThat(f.h.jdbc.queryForList("SELECT policy_version FROM lr_commission_entry WHERE agent_ref = 'D1' ORDER BY id", Integer.class))
                .containsExactly(1, 3);
    }

    @Test
    void floorDiffNeverGoesNegativeAndMissingFloorMeansZero() {
        f.policies.save(new PolicyInput("GLOBAL_DEFAULT", "FLOOR_DIFF", "GLOBAL", null, null, BigDecimal.ONE, BigDecimal.ZERO, false, 1, "DELIVERED",
                true, null), OP);
        f.policies.saveFloorPrice("GLOBAL_DEFAULT", new FloorPriceInput("1", null, f.c.skuANew, 12_000L), OP);
        f.deliveredCard("O-UNDER", 10_000, CHAIN);
        f.accruals.runOnce(50);
        assertThat(f.h.jdbc.queryForObject("SELECT outcome FROM lr_commission_accrual", String.class)).isEqualTo("ZERO");
        assertThat(f.h.count("lr_commission_entry")).isZero();
    }

    @Test
    void mostSpecificScopeWins() {
        f.paidRate("0.10", "1", "0", 1);
        f.policies.save(new PolicyInput("GRADE_1", "PAID_RATE", "AGENT_GRADE", "1", new BigDecimal("0.20"), BigDecimal.ONE, BigDecimal.ZERO, false, 1,
                "DELIVERED", true, null), OP);
        f.policies.save(new PolicyInput("SKU_A", "PAID_RATE", "SKU", Long.toString(f.c.skuANew), new BigDecimal("0.30"), BigDecimal.ONE,
                BigDecimal.ZERO, false, 1, "DELIVERED", true, null), OP);

        f.deliveredCard("O-SKU", 10_000, CHAIN);
        f.accruals.runOnce(50);
        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(3_000L);

        f.policies.save(new PolicyInput("SKU_A", "NONE", "SKU", Long.toString(f.c.skuANew), null, BigDecimal.ZERO, BigDecimal.ZERO, false, 1,
                "DELIVERED", true, null), OP);
        f.deliveredCard("O-SKU-OFF", 10_000, CHAIN);
        f.accruals.runOnce(50);
        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(3_000L);
    }

    @Test
    void settleWindowZeroIsImmediateAndPaidAnchorUsesLineTime() {
        f.policies.save(new PolicyInput("GLOBAL_DEFAULT", "PAID_RATE", "GLOBAL", null, new BigDecimal("0.10"), BigDecimal.ONE, BigDecimal.ZERO, false,
                0, "DELIVERED", true, null), OP);
        f.deliveredCard("O-T0", 10_000, CHAIN);
        f.accruals.runOnce(50);
        assertThat(f.statuses("D1", "ACCRUE")).containsExactly("SETTLEABLE");
        assertThat(f.ledger.balance("D1").settleableFen()).isEqualTo(1_000);

        f.policies.save(new PolicyInput("GLOBAL_DEFAULT", "PAID_RATE", "GLOBAL", null, new BigDecimal("0.10"), BigDecimal.ONE, BigDecimal.ZERO, false,
                2, "PAID", true, null), OP);
        f.h.importPoolA(1, true);
        long line = f.shopLine("O-T2", f.c.skuANew, 1, 10_000, CHAIN);
        // 收单时间取库默认（真实时钟），这里钉到测试时钟
        f.h.jdbc.update("UPDATE lr_fulfillment_line SET created_at = ? WHERE id = ?", java.time.LocalDateTime.ofInstant(f.now(), java.time.ZoneOffset.UTC),
                line);
        f.advance(Duration.ofDays(1));
        f.h.drain();
        f.accruals.runOnce(50);
        Instant settleAt = f.h.jdbc.queryForObject("SELECT settleable_at FROM lr_commission_entry WHERE agent_ref = 'D1' ORDER BY id DESC LIMIT 1",
                java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC);
        // 收单在 2026-11-22（东八区），T+2 = 2026-11-24 00:00 东八区，与交码日无关
        assertThat(settleAt).isEqualTo(Instant.parse("2026-11-23T16:00:00Z"));
        assertThat(line).isPositive();
    }

    // ---- 退款三情形（主计划 §5.3「P4/P5 测退款三种情形」）----

    @Test
    void refundBeforeDeliveryLeavesNothingToAccrueOrReverse() {
        f.paidRate("0.10", "0.8", "0.2", 1);
        f.h.importPoolA(1, false);
        long line = f.shopLine("O-R1", f.c.skuANew, 1, 10_000, CHAIN);
        f.h.drain();
        long card = f.h.cardOfLine(line);
        assertThat(f.h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");

        RefundCaseView rc = f.refunds.apply(card, null, "m-buyer", "不想要了");
        f.refunds.approve(rc.id(), OP);
        f.h.drain();
        f.refunds.complete(rc.id(), OP);

        assertThat(f.h.status("lr_card_asset", card)).isIn("TERMINATED", "CANCELLED");
        assertThat(f.accruals.runOnce(50)).isZero();
        assertThat(f.h.count("lr_commission_entry")).isZero();
    }

    @Test
    void refundOfDeliveredUnactivatedCardReversesPendingCommission() {
        f.paidRate("0.10", "0.8", "0.2", 1);
        long card = f.deliveredCard("O-R2", 10_000, CHAIN);
        f.accruals.runOnce(50);
        assertThat(f.ledger.balance("D1").pendingFen()).isEqualTo(800);

        RefundCaseView rc = f.refunds.apply(card, null, "m-buyer", null);
        assertThat(rc.decision()).isEqualTo("NEED_APPROVAL");
        f.refunds.approve(rc.id(), OP);
        f.h.drain();
        f.refunds.complete(rc.id(), OP);

        assertThat(f.h.status("lr_card_asset", card)).isEqualTo("TERMINATED");
        assertThat(f.amounts("D1", "REVERSE")).containsExactly(-800L);
        assertThat(f.amounts("D2", "REVERSE")).containsExactly(-200L);
        assertThat(f.statuses("D1", "REVERSE")).containsExactly("PENDING");
        assertThat(f.ledger.balance("D1").pendingFen()).isZero();
        assertThat(f.ledger.balance("D2").pendingFen()).isZero();

        f.advance(Duration.ofDays(2));
        f.ledger.settleDue();
        assertThat(f.ledger.balance("D1").settleableFen()).isZero();
    }

    @Test
    void exceptionalRefundOfActiveCardAfterWithdrawalLeavesNegativeBalance() {
        f.paidRate("0.10", "0.8", "0.2", 0);
        long card = f.deliveredCard("O-R3", 10_000, CHAIN);
        f.accruals.runOnce(50);
        Withdrawals.View w = f.withdrawals.apply(A1.memberId(), 800, new Withdrawals.Payee("ALIPAY", "张三", "13800001111"));
        f.withdrawals.approve(w.id(), OP);
        f.withdrawals.markPaid(w.id(), OP, "支付宝流水 2026112200001", null);
        assertThat(f.ledger.balance("D1").withdrawnFen()).isEqualTo(800);
        assertThat(f.ledger.balance("D1").settleableFen()).isZero();

        f.h.jdbc.update("UPDATE lr_card_asset SET status = 'IN_USE', activated_at = UTC_TIMESTAMP(3) WHERE id = ?", card);
        RefundCaseView rc = f.refunds.apply(card, null, "m-buyer", "人工例外");
        assertThat(rc.decision()).isEqualTo("DEFAULT_REJECT");
        f.refunds.approve(rc.id(), OP);
        f.h.drain();
        f.refunds.complete(rc.id(), OP);

        assertThat(f.h.status("lr_card_asset", card)).isEqualTo("TERMINATED");
        assertThat(f.amounts("D1", "REVERSE")).containsExactly(-800L);
        assertThat(f.statuses("D1", "REVERSE")).containsExactly("SETTLEABLE");
        assertThat(f.ledger.balance("D1").settleableFen()).isEqualTo(-800);
        assertThat(f.h.bean(CommissionAlerts.class).check(f.now())).anyMatch(a -> a.code().equals("AGENT_NEGATIVE_BALANCE"));

        f.deliveredCard("O-R3-NEXT", 10_000, CHAIN);
        f.accruals.runOnce(50);
        assertThat(f.ledger.balance("D1").settleableFen()).isZero();
    }

    @Test
    void partialRefundsReverseProportionallyAndLastOneSweepsRemainder() {
        f.paidRate("0.10", "1", "0", 1);
        f.h.importPoolA(3, true);
        long line = f.shopLine("O-QTY", f.c.skuANew, 3, 10_000, CHAIN);
        f.h.drain();
        f.accruals.runOnce(50);
        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(1_000L);
        List<Long> cards = f.h.jdbc.queryForList("SELECT id FROM lr_card_asset WHERE fulfillment_line_id = ? ORDER BY unit_seq", Long.class, line);

        for (long card : cards) {
            RefundCaseView rc = f.refunds.apply(card, null, "m-buyer", null);
            f.refunds.approve(rc.id(), OP);
            f.h.drain();
            f.refunds.complete(rc.id(), OP);
        }

        // 三张卡退款 3334 / 3333 / 3333：冲正 333 / 333 / 末笔轧差 334
        assertThat(f.amounts("D1", "REVERSE")).containsExactly(-333L, -333L, -334L);
        assertThat(f.ledger.balance("D1").pendingFen()).isZero();
    }

    @Test
    void cardsRefundedBeforeAccrualShrinkTheBase() {
        f.paidRate("0.10", "1", "0", 1);
        f.h.importPoolA(1, true);
        f.h.importPoolA(1, false);
        long line = f.shopLine("O-PART", f.c.skuANew, 2, 10_000, CHAIN);
        f.h.drain();
        List<Long> cards = f.h.jdbc.queryForList("SELECT id FROM lr_card_asset WHERE fulfillment_line_id = ? ORDER BY unit_seq", Long.class, line);
        long undelivered = cards.stream().filter(id -> "PROVISIONED".equals(f.h.status("lr_card_asset", id))).findFirst().orElseThrow();
        RefundCaseView rc = f.refunds.apply(undelivered, null, "m-buyer", null);
        f.refunds.approve(rc.id(), OP);
        f.h.drain();
        f.refunds.complete(rc.id(), OP);

        f.accruals.runOnce(50);
        assertThat(f.amounts("D1", "ACCRUE")).containsExactly(500L);
        assertThat(f.h.jdbc.queryForObject("SELECT base_fen FROM lr_commission_accrual", Long.class)).isEqualTo(5_000L);
    }

    @Test
    void policyInputIsValidated() {
        assertThatThrownBy(() -> f.policies.save(new PolicyInput("X", "MYSTERY", "GLOBAL", null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, false,
                1, "DELIVERED", true, null), OP)).isInstanceOf(UserFacingException.class).hasMessageContaining("佣金模式");
        assertThatThrownBy(() -> f.policies.save(new PolicyInput("X", "PAID_RATE", "GLOBAL", null, new BigDecimal("1.5"), BigDecimal.ONE,
                BigDecimal.ZERO, false, 1, "DELIVERED", true, null), OP)).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.policies.save(new PolicyInput("X", "PAID_RATE", "GLOBAL", null, new BigDecimal("0.1"), new BigDecimal("0.8"),
                new BigDecimal("0.3"), false, 1, "DELIVERED", true, null), OP)).isInstanceOf(UserFacingException.class).hasMessageContaining("合计");
        assertThatThrownBy(() -> f.policies.save(new PolicyInput("X", "PAID_RATE", "SKU", null, new BigDecimal("0.1"), BigDecimal.ONE,
                BigDecimal.ZERO, false, 1, "DELIVERED", true, null), OP)).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.policies.save(new PolicyInput("X", "PAID_RATE", "GLOBAL", null, new BigDecimal("0.1"), BigDecimal.ONE,
                BigDecimal.ZERO, false, -1, "DELIVERED", true, null), OP)).isInstanceOf(UserFacingException.class);
        assertThat(f.policies.current()).isEmpty();
        assertThat(f.h.count("lr_audit_log WHERE action = 'COMMISSION_POLICY_SAVE'")).isZero();

        f.paidRate("0.10", "1", "0", 1);
        assertThat(f.policies.current()).singleElement().satisfies(p -> {
            assertThat(p.version()).isEqualTo(1);
            assertThat(p.poolRate()).isEqualByComparingTo("0.10");
        });
        assertThat(f.h.count("lr_audit_log WHERE action = 'COMMISSION_POLICY_SAVE'")).isEqualTo(1);
        assertThat(Map.of("modes", f.policies.modes())).extractingByKey("modes").asList().contains("PAID_RATE", "FLOOR_DIFF", "NONE");
    }
}
