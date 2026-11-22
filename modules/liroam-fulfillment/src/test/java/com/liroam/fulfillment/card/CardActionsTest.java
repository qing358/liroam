package com.liroam.fulfillment.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.CardActions.PoolDisposition;
import com.liroam.fulfillment.card.CardActions.RefundEnd;
import com.liroam.fulfillment.card.CardActions.StatusRequest;
import com.liroam.fulfillment.state.FulfillmentLineStatus;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.fulfillment.worker.CommandExecutor;
import com.liroam.fulfillment.worker.FulfillmentWorker;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;

/** 卡动作：冻结/解冻、状态请求与降级（终止 → 停用 → 本地终止 + 对账待办）、取消未执行指令、整卡/套餐退款收尾。 */
class CardActionsTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static CardActions actions;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_card_actions");
        c = h.catalog;
        actions = h.bean(CardActions.class);
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    private long deliveredCardA(String order) {
        h.importPoolA(1, true);
        long card = h.acceptCard(order, c.skuANew, "m1");
        h.drain();
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        return card;
    }

    private int reconTasks(long card) {
        return h.count("lr_manual_task WHERE kind = 'RECON_DIFF' AND ref_type = 'CARD' AND ref_id = ?", Long.toString(card));
    }

    @Test
    void holdAndReleaseToggleRefundHoldWithAudit() {
        long card = deliveredCardA("O-1");

        assertThat(actions.hold(card, "op-1")).isTrue();
        assertThat(actions.hold(card, "op-1")).as("已冻结").isFalse();
        assertThat(h.jdbc.queryForObject("SELECT refund_hold FROM lr_card_asset WHERE id = ?", Boolean.class, card)).isTrue();
        actions.release(card, "op-1");
        assertThat(h.jdbc.queryForObject("SELECT refund_hold FROM lr_card_asset WHERE id = ?", Boolean.class, card)).isFalse();
        assertThat(h.count("lr_audit_log WHERE action IN ('CARD_HOLD', 'CARD_RELEASE') AND target_id = ?", Long.toString(card))).isEqualTo(3);

        assertThatThrownBy(() -> actions.hold(999_999, "op-1")).isInstanceOf(UserFacingException.class).hasMessage("找不到这张卡");
    }

    @Test
    void supportedStatusOpsAreQueuedAndExecutedUpstream() {
        long card = deliveredCardA("O-2");

        assertThat(actions.requestStatus(card, StatusOp.SUSPEND, "op")).isEqualTo(StatusRequest.QUEUED);
        h.drain();
        assertThat(h.status("lr_card_asset", card)).isEqualTo("SUSPENDED");
        assertThat(actions.requestStatus(card, StatusOp.RESUME, "op")).isEqualTo(StatusRequest.QUEUED);
        h.drain();
        assertThat(h.status("lr_card_asset", card)).as("未激活的卡恢复回待激活").isEqualTo("DELIVERED");
        assertThat(actions.requestStatus(card, StatusOp.TERMINATE, "op")).isEqualTo(StatusRequest.QUEUED);
        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("TERMINATED");
        assertThat(h.count("lr_supplier_call_log WHERE operation IN ('SUSPEND', 'RESUME', 'TERMINATE')")).isEqualTo(3);
        assertThat(reconTasks(card)).isZero();
        assertThatThrownBy(() -> actions.requestStatus(card, StatusOp.SUSPEND, "op")).isInstanceOf(UserFacingException.class)
                .hasMessage("这张卡当前状态不能停用");
    }

    @Test
    void terminateDegradesToSuspendThenToLocalOnlyAndOpensReconTask() {
        long suspendable = deliveredCardA("O-3");
        h.capsA.statusOps(Set.of(StatusOp.SUSPEND, StatusOp.RESUME));

        assertThat(actions.requestStatus(suspendable, StatusOp.TERMINATE, "op")).isEqualTo(StatusRequest.DEGRADED_TO_SUSPEND);
        assertThat(h.command("SUSPEND", suspendable)).containsEntry("status", "PENDING");
        assertThat(h.count("lr_supplier_command WHERE type = 'TERMINATE'")).isZero();
        assertThat(reconTasks(suspendable)).isEqualTo(1);
        h.drain();
        assertThat(h.status("lr_card_asset", suspendable)).isEqualTo("SUSPENDED");

        long bare = deliveredCardA("O-4");
        h.capsA.statusOps(Set.of());
        assertThat(actions.requestStatus(bare, StatusOp.TERMINATE, "op")).isEqualTo(StatusRequest.LOCAL_ONLY);
        assertThat(h.jdbc.queryForMap("SELECT status, poll_tier, next_poll_at FROM lr_card_asset WHERE id = ?", bare))
                .containsEntry("status", "TERMINATED").containsEntry("poll_tier", null).containsEntry("next_poll_at", null);
        assertThat(h.count("lr_supplier_command WHERE card_asset_id = ? AND type IN ('SUSPEND', 'TERMINATE')", bare)).isZero();
        assertThat(reconTasks(bare)).isEqualTo(1);
        assertThat(h.count("lr_audit_log WHERE action = 'CARD_STATUS_REQUEST'")).isEqualTo(2);
    }

    @Test
    void unsupportedSuspendOrResumeIsRefusedAndUnprovisionedCardCannotChangeStatus() {
        long b = h.acceptCard("O-5", c.skuBNew, "m1");
        assertThatThrownBy(() -> actions.requestStatus(b, StatusOp.TERMINATE, "op")).isInstanceOf(UserFacingException.class)
                .hasMessage("这张卡当前状态不能终止");
        h.drain();

        assertThatThrownBy(() -> actions.requestStatus(b, StatusOp.SUSPEND, "op")).isInstanceOf(UserFacingException.class)
                .hasMessage("该供应商不支持停用");
        assertThatThrownBy(() -> actions.requestStatus(b, StatusOp.RESUME, "op")).isInstanceOf(UserFacingException.class)
                .hasMessage("该供应商不支持恢复");
        assertThat(h.count("lr_supplier_command WHERE type IN ('SUSPEND', 'RESUME')")).isZero();
        assertThat(actions.requestStatus(b, StatusOp.TERMINATE, "op")).isEqualTo(StatusRequest.QUEUED);
    }

    @Test
    void cancelPendingCancelsOpenCommandsOfCardOrSinglePlan() {
        long card = h.acceptCard("O-6", c.skuANew, "m1");
        assertThat(h.command("ALLOCATE", card)).containsEntry("status", "PENDING");

        assertThat(actions.cancelPending(card, null, "op")).isEqualTo(1);
        assertThat(h.command("ALLOCATE", card)).containsEntry("status", "CANCELLED");
        assertThat(actions.cancelPending(card, null, "op")).isZero();
    }

    @Test
    void refundOfUnprovisionedCardCancelsEverything() {
        long card = h.acceptCard("O-7", c.skuANew, "m1");
        h.drain();
        assertThat(h.command("ALLOCATE", card)).as("池空推迟").containsEntry("status", "PENDING");
        actions.hold(card, "op");

        actions.finalizeRefund(card, RefundEnd.CANCELLED, PoolDisposition.VOID, "op");

        assertThat(h.jdbc.queryForMap("SELECT status, refund_hold FROM lr_card_asset WHERE id = ?", card)).containsEntry("status", "CANCELLED")
                .containsEntry("refund_hold", false);
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE card_asset_id = ?", String.class, card)).isEqualTo("CANCELLED");
        assertThat(h.command("ALLOCATE", card)).containsEntry("status", "CANCELLED");
        assertThat(h.count("lr_audit_log WHERE action = 'CARD_REFUND_FINALIZE' AND target_id = ?", Long.toString(card))).isEqualTo(1);
    }

    @Test
    void refundReturnsUnprovisionedPoolCardForReuseAndVoidsDeliveredOne() {
        h.importPoolA(1, true);
        long returned = h.acceptCard("O-8", c.skuANew, "m1");
        h.bean(CommandExecutor.class).runOnce(1, "test-worker");
        assertThat(h.status("lr_card_asset", returned)).as("已挂池卡、未开通").isEqualTo("ALLOCATED");

        actions.finalizeRefund(returned, RefundEnd.CANCELLED, PoolDisposition.RETURN, "op");

        Map<String, Object> row = h.jdbc.queryForMap("SELECT status, iccid_hash, poll_tier, next_poll_at FROM lr_card_asset WHERE id = ?", returned);
        assertThat(row).containsEntry("status", "CANCELLED").containsEntry("iccid_hash", null).containsEntry("poll_tier", null)
                .containsEntry("next_poll_at", null);
        assertThat(h.command("PROVISION", returned)).containsEntry("status", "CANCELLED");
        assertThat(h.count("lr_card_pool_item WHERE status = 'IN_STOCK' AND card_asset_id IS NULL")).as("归还可再分配").isEqualTo(1);
        long reuse = h.acceptCard("O-9", c.skuANew, "m2");
        h.drain();
        assertThat(h.status("lr_card_asset", reuse)).isEqualTo("DELIVERED");
        assertThatThrownBy(() -> actions.finalizeRefund(reuse, RefundEnd.CANCELLED, PoolDisposition.VOID, "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("这张卡当前状态（待激活）不能作废");

        actions.finalizeRefund(reuse, RefundEnd.TERMINATED, PoolDisposition.VOID, "op");
        assertThat(h.count("lr_card_pool_item WHERE status = 'VOID'")).isEqualTo(1);
        assertThat(h.status("lr_card_asset", reuse)).isEqualTo("TERMINATED");
        actions.finalizeRefund(reuse, RefundEnd.TERMINATED, PoolDisposition.VOID, "op");
        assertThat(h.status("lr_card_asset", reuse)).as("重复收尾幂等").isEqualTo("TERMINATED");
    }

    @Test
    void planRefundCancelsPendingTopUpButHandsReadyPlanToManual() {
        long card = deliveredCardA("O-10");
        long pendingLine = h.acceptTopUp("O-10T", "O-10T-1", c.skuATopUp, "m1", card);
        h.bean(FulfillmentWorker.class).runOnce(10);
        long pending = h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE fulfillment_line_id = ?", Long.class, pendingLine);

        assertThat(actions.finalizePlanRefund(pending, "op")).isTrue();
        assertThat(h.status("lr_card_plan", pending)).isEqualTo("CANCELLED");
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_supplier_command WHERE card_plan_id = ?", String.class, pending)).isEqualTo("CANCELLED");

        long base = h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE card_asset_id = ? AND kind = 'BASE'", Long.class, card);
        assertThat(actions.finalizePlanRefund(base, "op")).isFalse();
        assertThat(h.status("lr_card_plan", base)).isEqualTo("READY");
        assertThat(h.count("lr_manual_task WHERE kind = 'REFUND_EXCEPTION' AND ref_type = 'CARD_PLAN' AND ref_id = ?", Long.toString(base))).isEqualTo(1);
        assertThatThrownBy(() -> actions.finalizePlanRefund(999_999, "op")).isInstanceOf(UserFacingException.class).hasMessage("找不到这个套餐");
    }

    @Test
    void refundedLineIsCancelledOnlyWhenEveryCardEndedOtherwisePartiallyRefunded() {
        h.importPoolA(3, true);
        long line = h.acceptShop("O-11", "O-11-1", c.skuANew, 2, "m1");
        h.drain();
        List<Long> ids = h.jdbc.queryForList("SELECT id FROM lr_card_asset WHERE fulfillment_line_id = ? ORDER BY unit_seq", Long.class, line);
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("COMPLETED");

        actions.finalizeRefund(ids.get(0), RefundEnd.TERMINATED, PoolDisposition.VOID, "op");
        assertThat(actions.settleRefundedLine(ids.get(0), null, "op")).isEqualTo(FulfillmentLineStatus.PARTIAL_REFUNDED);
        assertThat(actions.settleRefundedLine(ids.get(0), null, "op")).as("重复调用不动").isEqualTo(FulfillmentLineStatus.PARTIAL_REFUNDED);
        actions.finalizeRefund(ids.get(1), RefundEnd.TERMINATED, PoolDisposition.VOID, "op");
        assertThat(actions.settleRefundedLine(ids.get(1), null, "op")).as("行内卡都已退").isEqualTo(FulfillmentLineStatus.CANCELLED);
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("CANCELLED");
        assertThat(h.count("lr_audit_log WHERE action = 'LINE_REFUND_SETTLE' AND target_id = ?", Long.toString(line))).isEqualTo(2);

        long card = h.acceptCard("O-12", c.skuANew, "m1");
        h.drain();
        long topUpLine = h.acceptTopUp("O-12T", "O-12T-1", c.skuATopUp, "m1", card);
        h.bean(FulfillmentWorker.class).runOnce(10);
        long plan = h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE fulfillment_line_id = ?", Long.class, topUpLine);
        actions.finalizePlanRefund(plan, "op");
        assertThat(actions.settleRefundedLine(card, plan, "op")).as("加包行即该套餐").isEqualTo(FulfillmentLineStatus.CANCELLED);
        assertThat(h.status("lr_fulfillment_line", topUpLine)).isEqualTo("CANCELLED");
        assertThat(h.status("lr_fulfillment_line", h.jdbc.queryForObject("SELECT fulfillment_line_id FROM lr_card_asset WHERE id = ?", Long.class,
                card))).as("原卡行不动").isEqualTo("COMPLETED");
    }
}
