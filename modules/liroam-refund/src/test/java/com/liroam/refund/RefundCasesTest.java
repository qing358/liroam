package com.liroam.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.worker.CommandExecutor;
import com.liroam.fulfillment.worker.FulfillmentWorker;
import com.liroam.refund.RefundFixture.Reversal;
import com.liroam.refund.RefundRules.CardAction;
import com.liroam.refund.RefundRules.Decision;
import com.liroam.refund.RefundRules.TargetType;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;

/** CT-REF-01（§9.2 每行一个用例）、V11（A 第 2 张卡未激活退款全链）、驳回/重复/他人卡/自动审核开关/无快照/无规则。 */
class RefundCasesTest {

    private static RefundFixture f;
    private static final String OP = "manager:admin";

    @BeforeAll
    static void start() {
        f = RefundFixture.start("refund_cases");
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    /**
     * §9.2 一行：布置出该状态的卡（或卡 + 加包）→ 申请 → 批准 → 跑指令 → 完成。
     *
     * @param pool 池卡最终状态；"NONE" = 没有池卡；null = 不查（加包行）
     */
    record Row(String label, Function<RefundFixture, long[]> arrange, Decision decision, CardAction cardAction, String poolAction, boolean reverse,
            String finalCard, String finalPlan, String pool) {

        @Override
        public String toString() {
            return label;
        }
    }

    private static long[] created(RefundFixture f) {
        return new long[] {f.h.acceptCard("O-C", f.c.skuANew, "m1")};
    }

    private static long[] allocated(RefundFixture f) {
        f.h.importPoolA(1, true);
        long card = f.h.acceptCard("O-A", f.c.skuANew, "m1");
        f.h.bean(CommandExecutor.class).runOnce(1, "test-worker");
        assertThat(f.cardStatus(card)).isEqualTo("ALLOCATED");
        return new long[] {card};
    }

    private static long[] provisioned(RefundFixture f) {
        f.h.importPoolA(1, false);
        long card = f.h.acceptCard("O-P", f.c.skuANew, "m1");
        f.h.drain();
        assertThat(f.cardStatus(card)).isEqualTo("PROVISIONED");
        return new long[] {card};
    }

    private static long[] delivered(RefundFixture f) {
        long card = f.deliveredA("O-D", "m1");
        assertThat(f.cardStatus(card)).isEqualTo("DELIVERED");
        return new long[] {card};
    }

    private static long[] inUse(RefundFixture f) {
        long card = delivered(f)[0];
        f.h.jdbc.update("UPDATE lr_card_asset SET status = 'IN_USE', activated_at = UTC_TIMESTAMP(3) WHERE id = ?", card);
        return new long[] {card};
    }

    private static long[] suspended(RefundFixture f) {
        long card = delivered(f)[0];
        f.h.bean(CardActions.class).requestStatus(card, StatusOp.SUSPEND, "op");
        f.h.drain();
        assertThat(f.cardStatus(card)).isEqualTo("SUSPENDED");
        return new long[] {card};
    }

    private static long[] expired(RefundFixture f) {
        long card = delivered(f)[0];
        f.h.jdbc.update("UPDATE lr_card_asset SET status = 'EXPIRED' WHERE id = ?", card);
        return new long[] {card};
    }

    private static long[] topUp(RefundFixture f, String planStatus, boolean drain) {
        long card = delivered(f)[0];
        long line = f.h.acceptTopUp("O-T", "O-T-1", f.c.skuATopUp, "m1", card);
        f.h.bean(FulfillmentWorker.class).runOnce(10);
        if (drain) {
            f.h.drain();
        }
        long plan = f.h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE fulfillment_line_id = ?", Long.class, line);
        f.h.jdbc.update("UPDATE lr_card_plan SET status = ? WHERE id = ?", planStatus, plan);
        if ("IN_USE".equals(planStatus)) {
            f.h.jdbc.update("UPDATE lr_card_asset SET status = 'IN_USE', activated_at = UTC_TIMESTAMP(3) WHERE id = ?", card);
        }
        return new long[] {card, plan};
    }

    static Stream<Row> ctRef01() {
        return Stream.of(
                new Row("CREATED", RefundCasesTest::created, Decision.AUTO_APPROVE, CardAction.CANCEL, "RETURN", true, "CANCELLED", null, "NONE"),
                new Row("ALLOCATED", RefundCasesTest::allocated, Decision.AUTO_APPROVE, CardAction.CANCEL, "RETURN", true, "CANCELLED", null,
                        "IN_STOCK"),
                new Row("PROVISIONED", RefundCasesTest::provisioned, Decision.AUTO_APPROVE, CardAction.TERMINATE, "VOID", true, "TERMINATED", null,
                        "VOID"),
                new Row("DELIVERED", RefundCasesTest::delivered, Decision.NEED_APPROVAL, CardAction.TERMINATE, "VOID", true, "TERMINATED", null,
                        "VOID"),
                new Row("IN_USE（人工例外批准）", RefundCasesTest::inUse, Decision.DEFAULT_REJECT, CardAction.TERMINATE, null, true, "TERMINATED", null,
                        "VOID"),
                new Row("SUSPENDED（人工例外批准）", RefundCasesTest::suspended, Decision.DEFAULT_REJECT, CardAction.TERMINATE, null, true, "TERMINATED",
                        null, "VOID"),
                new Row("EXPIRED（人工例外批准）", RefundCasesTest::expired, Decision.DEFAULT_REJECT, CardAction.NONE, null, false, "TERMINATED", null,
                        "VOID"),
                new Row("加包 PENDING", x -> topUp(x, "PENDING", false), Decision.AUTO_APPROVE, CardAction.CANCEL, null, true, "DELIVERED",
                        "CANCELLED", null),
                new Row("加包 PROVISIONING", x -> topUp(x, "PROVISIONING", false), Decision.AUTO_APPROVE, CardAction.CANCEL, null, true, "DELIVERED",
                        "CANCELLED", null),
                new Row("加包 READY（撤不了转人工）", x -> topUp(x, "READY", true), Decision.NEED_APPROVAL, CardAction.CANCEL, null, true, "DELIVERED",
                        "READY", null),
                new Row("加包 IN_USE（人工例外批准）", x -> topUp(x, "IN_USE", true), Decision.DEFAULT_REJECT, CardAction.NONE, null, false, "IN_USE",
                        "IN_USE", null));
    }

    @ParameterizedTest(name = "CT-REF-01 {0}")
    @MethodSource("ctRef01")
    void ct_ref_01_defaultRuleRow(Row row) {
        long[] ids = row.arrange().apply(f);
        long card = ids[0];
        Long plan = ids.length > 1 ? ids[1] : null;

        RefundCaseView applied = f.cases.apply(card, plan, "m1", "行程取消");

        assertThat(applied).extracting(RefundCaseView::status, RefundCaseView::decision, RefundCaseView::cardPlanId, RefundCaseView::reason)
                .containsExactly("HELD", row.decision().name(), plan, "行程取消");
        assertThat(f.held(card)).isTrue();
        Map<String, Object> rc = f.h.jdbc.queryForMap("SELECT target_type, card_action, pool_action, reverse_commission, rule_version "
                + "FROM lr_refund_case WHERE id = ?", applied.id());
        assertThat(rc).containsEntry("target_type", plan == null ? "CARD" : "PLAN").containsEntry("card_action", row.cardAction().name())
                .containsEntry("pool_action", row.poolAction()).containsEntry("reverse_commission", row.reverse()).containsEntry("rule_version", 1);

        assertThat(f.cases.approve(applied.id(), OP).status()).isEqualTo("EXECUTING");
        f.h.drain();
        RefundCaseView done = f.cases.complete(applied.id(), OP);

        assertThat(done.status()).isEqualTo("DONE");
        assertThat(f.cardStatus(card)).isEqualTo(row.finalCard());
        assertThat(f.held(card)).as("收尾解冻").isFalse();
        if (plan != null) {
            assertThat(f.h.status("lr_card_plan", plan)).isEqualTo(row.finalPlan());
        }
        if (row.pool() != null) {
            List<String> pool = f.h.jdbc.queryForList("SELECT status FROM lr_card_pool_item", String.class);
            assertThat(pool).isEqualTo("NONE".equals(row.pool()) ? List.of() : List.of(row.pool()));
        }
        assertThat(f.reversal.calls).hasSize(row.reverse() ? 1 : 0);
        String lineSql = plan == null ? "SELECT l.status FROM lr_fulfillment_line l JOIN lr_card_asset c ON c.fulfillment_line_id = l.id WHERE c.id = ?"
                : "SELECT l.status FROM lr_fulfillment_line l JOIN lr_card_plan p ON p.fulfillment_line_id = l.id WHERE p.id = ?";
        assertThat(f.h.jdbc.queryForObject(lineSql, String.class, plan == null ? card : plan)).isEqualTo("CANCELLED");
    }

    @Test
    void ct_ref_01_readyTopUpThatCannotBeUndoneGoesToManual() {
        long[] ids = topUp(f, "READY", true);
        long id = f.cases.apply(ids[0], ids[1], "m1", null).id();
        f.cases.approve(id, OP);
        f.cases.complete(id, OP);

        assertThat(f.h.count("lr_manual_task WHERE kind = 'REFUND_EXCEPTION' AND ref_type = 'CARD_PLAN' AND ref_id = ?", Long.toString(ids[1])))
                .isEqualTo(1);
        assertThat(f.h.jdbc.queryForList("SELECT status FROM lr_card_plan WHERE card_asset_id = ? ORDER BY id", String.class, ids[0]))
                .as("加包退款不动同卡其它套餐").containsExactly("READY", "READY");
    }

    @Test
    void ct_ref_01_terminatedCardRuleIsDefaultRejectAndCannotBeAppliedAgain() {
        long card = delivered(f)[0];
        f.h.bean(CardActions.class).requestStatus(card, StatusOp.TERMINATE, "op");
        f.h.drain();

        assertThat(f.rules.decide("TERMINATED", TargetType.CARD)).extracting(RefundRules.Rule::decision, RefundRules.Rule::cardAction)
                .containsExactly(Decision.DEFAULT_REJECT, CardAction.NONE);
        assertThatThrownBy(() -> f.cases.apply(card, null, "m1", null)).isInstanceOf(UserFacingException.class)
                .hasMessage("这张卡已终止，不能再申请退款");
    }

    @Test
    void ct_ref_01_noRuleMeansNeedApprovalAndNoCardAction() {
        long card = delivered(f)[0];
        f.h.jdbc.update("DELETE FROM lr_refund_rule");

        RefundCaseView applied = f.cases.apply(card, null, "m1", null);

        assertThat(applied.decision()).isEqualTo("NEED_APPROVAL");
        assertThat(applied.decisionText()).isEqualTo("需人工审核");
        assertThat(f.h.jdbc.queryForMap("SELECT card_action, pool_action, rule_version FROM lr_refund_case WHERE id = ?", applied.id()))
                .containsEntry("card_action", "NONE").containsEntry("pool_action", null).containsEntry("rule_version", 0);
        f.cases.approve(applied.id(), OP);
        assertThat(f.h.count("lr_supplier_command WHERE card_asset_id = ? AND type IN ('SUSPEND', 'TERMINATE')", card)).isZero();
    }

    @Test
    void v11_secondCardOfShapeA_notActivated_heldApprovedTerminatedRefunded() {
        f.h.importPoolA(2, true);
        long line = f.h.acceptShop("O-V11", "O-V11-1", f.c.skuANew, 2, "m1");
        f.h.drain();
        List<Long> ids = f.h.jdbc.queryForList("SELECT id FROM lr_card_asset WHERE fulfillment_line_id = ? ORDER BY unit_seq", Long.class, line);
        long first = ids.get(0);
        long second = ids.get(1);
        f.snapshot("SHOP", "O-V11-1", f.c.skuANew, 3001, 2);
        assertThat(f.cardStatus(second)).isEqualTo("DELIVERED");

        RefundCaseView applied = f.cases.apply(second, null, "m1", "没出行");
        assertThat(applied).extracting(RefundCaseView::status, RefundCaseView::statusText, RefundCaseView::decision, RefundCaseView::refundFen)
                .containsExactly("HELD", "待审核", "NEED_APPROVAL", 1500L);
        assertThat(f.held(second)).isTrue();
        assertThat(f.held(first)).isFalse();

        assertThat(f.cases.approve(applied.id(), OP).status()).isEqualTo("EXECUTING");
        assertThat(f.h.command("TERMINATE", second)).containsEntry("status", "PENDING");
        assertThatThrownBy(() -> f.cases.complete(applied.id(), OP)).isInstanceOf(UserFacingException.class)
                .hasMessage("停卡指令还未执行完成，请稍后再完成退款");

        f.h.drain();
        assertThat(f.cardStatus(second)).isEqualTo("TERMINATED");
        RefundCaseView done = f.cases.complete(applied.id(), OP);

        assertThat(done).extracting(RefundCaseView::status, RefundCaseView::statusText).containsExactly("DONE", "已退款");
        assertThat(f.h.jdbc.queryForMap("SELECT status, refund_hold FROM lr_card_asset WHERE id = ?", second)).containsEntry("status", "TERMINATED")
                .containsEntry("refund_hold", false);
        assertThat(f.h.jdbc.queryForList("SELECT status FROM lr_card_pool_item ORDER BY id", String.class)).containsExactlyInAnyOrder("ALLOCATED",
                "VOID");
        assertThat(f.reversal.calls).containsExactly(new Reversal(applied.id(), "O-V11-1", 1500, 3001));
        assertThat(f.cardStatus(first)).as("同行另一张卡不受影响").isEqualTo("DELIVERED");
        assertThat(f.h.status("lr_fulfillment_line", line)).isEqualTo("PARTIAL_REFUNDED");
        assertThat(f.h.jdbc.queryForObject("SELECT decided_by FROM lr_refund_case WHERE id = ?", String.class, applied.id())).isEqualTo(OP);

        long firstCase = f.cases.apply(first, null, "m1", null).id();
        assertThat(f.cases.view(firstCase).refundFen()).as("首张卡吸收除不尽的分").isEqualTo(1501);
        assertThatThrownBy(() -> f.cases.complete(applied.id(), OP)).isInstanceOf(UserFacingException.class).hasMessage("这笔退款已退款，不能完成");
    }

    @Test
    void terminateDegradesToSuspendOrLocalOnlyAndCompleteWaitsForSuspend() {
        f.h.capsA.statusOps(java.util.Set.of(StatusOp.SUSPEND, StatusOp.RESUME));
        long degraded = delivered(f)[0];
        long id = f.cases.apply(degraded, null, "m1", null).id();
        f.cases.approve(id, OP);

        assertThat(f.h.jdbc.queryForObject("SELECT status_request FROM lr_refund_case WHERE id = ?", String.class, id))
                .isEqualTo("DEGRADED_TO_SUSPEND");
        assertThatThrownBy(() -> f.cases.complete(id, OP)).isInstanceOf(UserFacingException.class)
                .hasMessage("停卡指令还未执行完成，请稍后再完成退款");
        f.h.drain();
        assertThat(f.cardStatus(degraded)).isEqualTo("SUSPENDED");
        f.cases.complete(id, OP);
        assertThat(f.cardStatus(degraded)).as("停用后本地终止").isEqualTo("TERMINATED");
        assertThat(f.h.count("lr_manual_task WHERE kind = 'RECON_DIFF' AND ref_id = ?", Long.toString(degraded))).isEqualTo(1);

        f.h.capsA.statusOps(java.util.Set.of());
        long local = f.deliveredA("O-L", "m1");
        long localCase = f.cases.apply(local, null, "m1", null).id();
        f.cases.approve(localCase, OP);
        assertThat(f.h.jdbc.queryForObject("SELECT status_request FROM lr_refund_case WHERE id = ?", String.class, localCase))
                .isEqualTo("LOCAL_ONLY");
        assertThat(f.cases.complete(localCase, OP).status()).isEqualTo("DONE");
        assertThat(f.cardStatus(local)).isEqualTo("TERMINATED");
    }

    @Test
    void rejectReleasesHoldAndCardCanBeAppliedAgain() {
        long card = delivered(f)[0];
        long id = f.cases.apply(card, null, "m1", null).id();

        RefundCaseView rejected = f.cases.reject(id, OP);

        assertThat(rejected).extracting(RefundCaseView::status, RefundCaseView::statusText).containsExactly("RELEASED", "未通过");
        assertThat(f.held(card)).isFalse();
        assertThat(f.cardStatus(card)).isEqualTo("DELIVERED");
        assertThat(f.reversal.calls).isEmpty();
        assertThatThrownBy(() -> f.cases.reject(id, OP)).isInstanceOf(UserFacingException.class).hasMessage("这笔退款未通过，不能驳回");
        assertThatThrownBy(() -> f.cases.approve(id, OP)).isInstanceOf(UserFacingException.class).hasMessage("这笔退款未通过，不能审批");
        assertThatThrownBy(() -> f.cases.complete(id, OP)).isInstanceOf(UserFacingException.class).hasMessage("这笔退款未通过，不能完成");

        long again = f.cases.apply(card, null, "m1", null).id();
        assertThat(f.h.jdbc.queryForList("SELECT source_ref FROM lr_refund_case ORDER BY id", String.class))
                .containsExactly("CARD:" + card + ":1", "CARD:" + card + ":2");
        assertThat(again).isNotEqualTo(id);
    }

    @Test
    void duplicateApplyAndOthersCardAndClosedTargetsAreRefused() {
        long card = delivered(f)[0];
        long others = f.deliveredA("O-X", "m2");
        f.cases.apply(card, null, "m1", null);

        assertThatThrownBy(() -> f.cases.apply(card, null, "m1", null)).isInstanceOf(UserFacingException.class).hasMessage("这张卡已有退款在处理中");
        assertThatThrownBy(() -> f.cases.apply(others, null, "m1", null)).isInstanceOf(UserFacingException.class).hasMessage("找不到这张卡");
        assertThatThrownBy(() -> f.cases.apply(999_999, null, "m1", null)).isInstanceOf(UserFacingException.class).hasMessage("找不到这张卡");
        assertThat(f.h.count("lr_refund_case")).isEqualTo(1);
        assertThat(f.held(others)).isFalse();

        long base = f.h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE card_asset_id = ?", Long.class, others);
        assertThatThrownBy(() -> f.cases.apply(others, base, "m2", null)).isInstanceOf(UserFacingException.class).hasMessage("首购套餐请整卡退款");
        assertThatThrownBy(() -> f.cases.apply(others, 999_999L, "m2", null)).isInstanceOf(UserFacingException.class).hasMessage("找不到这个加包");
    }

    @Test
    void autoReviewOffKeepsAutoApproveSuggestionHeld() {
        long card = created(f)[0];

        RefundCaseView applied = f.cases.apply(card, null, "m1", null);

        assertThat(applied).extracting(RefundCaseView::status, RefundCaseView::decision, RefundCaseView::decisionText)
                .containsExactly("HELD", "AUTO_APPROVE", "建议通过");
        assertThat(f.h.command("ALLOCATE", card)).containsEntry("status", "PENDING");
    }

    @Test
    void autoReviewOnApprovesOnlyAutoApproveSuggestions() {
        f.h.bean(Settings.class).set(RefundSettings.AUTO_REVIEW, SettingScope.GLOBAL, true, OP);
        long delivered = f.deliveredA("O-D2", "m1");
        long fresh = created(f)[0];

        RefundCaseView auto = f.cases.apply(fresh, null, "m1", null);
        RefundCaseView manual = f.cases.apply(delivered, null, "m1", null);

        assertThat(auto).extracting(RefundCaseView::status, RefundCaseView::decision).containsExactly("EXECUTING", "AUTO_APPROVE");
        assertThat(f.h.jdbc.queryForObject("SELECT decided_by FROM lr_refund_case WHERE id = ?", String.class, auto.id()))
                .isEqualTo(RefundCases.AUTO_REVIEWER);
        assertThat(f.h.command("ALLOCATE", fresh)).containsEntry("status", "CANCELLED");
        assertThat(manual.status()).as("需人工审核的不自动批").isEqualTo("HELD");
        assertThat(f.h.count("lr_supplier_command WHERE card_asset_id = ? AND type = 'TERMINATE'", delivered)).isZero();
    }

    @Test
    void autoApproveThatCannotRunStaysHeldForManualReview() {
        f.h.bean(Settings.class).set(RefundSettings.AUTO_REVIEW, SettingScope.GLOBAL, true, OP);
        f.rules.save(new RefundRules.RuleInput(null, "CREATED", "CARD", "AUTO_APPROVE", "TERMINATE", null, true, 1), OP);
        long card = created(f)[0];

        RefundCaseView applied = f.cases.apply(card, null, "m1", null);

        assertThat(applied.status()).isEqualTo("HELD");
        assertThat(f.held(card)).isTrue();
        assertThat(f.h.jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(after_json, '$.message')) FROM lr_audit_log "
                + "WHERE action = 'REFUND_AUTO_APPROVE_FAILED' AND target_id = ?", String.class, Long.toString(applied.id())))
                .isEqualTo("这张卡当前状态不能终止");
    }

    @Test
    void missingSnapshotRefundsZeroAndIsMarked() {
        long card = delivered(f)[0];

        RefundCaseView applied = f.cases.apply(card, null, "m1", null);

        assertThat(applied.refundFen()).isZero();
        assertThat(f.h.jdbc.queryForMap("SELECT paid_fen, refund_basis FROM lr_refund_case WHERE id = ?", applied.id()))
                .containsEntry("paid_fen", 0L).containsEntry("refund_basis", "NO_SNAPSHOT");
        assertThat(f.cases.page(null, 1, 10).records().getFirst().refundBasis()).isEqualTo("NO_SNAPSHOT");
    }

    @Test
    void applyRequestsOnDemandRefreshOfDeliveredCard() {
        long card = delivered(f)[0];

        f.cases.apply(card, null, "m1", null);

        assertThat(f.h.command("QUERY", card)).containsEntry("priority", 1);
        assertThat(f.h.count("lr_audit_log WHERE action = 'REFUND_APPLY' AND actor = 'member:m1'")).isEqualTo(1);
    }
}
