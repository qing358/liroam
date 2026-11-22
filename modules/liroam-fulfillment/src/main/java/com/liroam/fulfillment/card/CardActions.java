package com.liroam.fulfillment.card;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.SupplierAdapterRegistry;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.pool.CardPool;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.CardPlanStatus;
import com.liroam.fulfillment.state.CommandPriority;
import com.liroam.fulfillment.state.CommandType;
import com.liroam.fulfillment.state.FulfillmentLineStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.CardPlans;
import com.liroam.fulfillment.store.CardPlans.Plan;
import com.liroam.fulfillment.store.FulfillmentLines;
import com.liroam.fulfillment.store.SupplierCommands;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.supplier.spi.SupplierCapabilities;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;

/**
 * 卡动作（退款与后台用）：冻结/解冻、请求上游停用/恢复/终止（不支持终止时降级为停用，再不支持则只在本地终止，均开对账待办）、
 * 取消未执行的指令、退款收尾（作废或终止、池卡归还或作废）与履约行推进。套餐级同理。每个方法自成事务（调用方已开事务则加入）并写审计。
 */
public class CardActions {

    /** {@link #requestStatus} 实际做了什么。 */
    public enum StatusRequest {
        /** 已排上游指令，由指令 worker 执行。 */
        QUEUED,
        /** 供应商不支持终止，已改排停用，并开对账待办。 */
        DEGRADED_TO_SUSPEND,
        /** 供应商停用/终止都不支持，只在本地标终止，并开对账待办。 */
        LOCAL_ONLY
    }

    public enum RefundEnd { CANCELLED, TERMINATED }

    /** 池卡的去向：归还可再分配，或作废。 */
    public enum PoolDisposition { RETURN, VOID }

    private static final Set<CardAssetStatus> UPSTREAM_LIVE = EnumSet.of(CardAssetStatus.PROVISIONED, CardAssetStatus.DELIVERED,
            CardAssetStatus.IN_USE, CardAssetStatus.SUSPENDED, CardAssetStatus.EXPIRED);
    private static final Set<CardAssetStatus> REFUND_ENDS = EnumSet.of(CardAssetStatus.CANCELLED, CardAssetStatus.TERMINATED);

    private final TransactionOperations tx;
    private final CardAssets cards;
    private final CardPlans plans;
    private final FulfillmentLines lines;
    private final SupplierCommands commands;
    private final SupplierDirectory suppliers;
    private final SupplierAdapterRegistry adapters;
    private final CardPool pool;
    private final ManualTasks tasks;
    private final Settings settings;
    private final AuditLog audit;
    private final Clock clock;

    public CardActions(TransactionOperations tx, CardAssets cards, CardPlans plans, FulfillmentLines lines, SupplierCommands commands,
            SupplierDirectory suppliers, SupplierAdapterRegistry adapters, CardPool pool, ManualTasks tasks, Settings settings, AuditLog audit,
            Clock clock) {
        this.tx = tx;
        this.cards = cards;
        this.plans = plans;
        this.lines = lines;
        this.commands = commands;
        this.suppliers = suppliers;
        this.adapters = adapters;
        this.pool = pool;
        this.tasks = tasks;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /** 退款冻结：开通类指令暂停执行、不能加包、不出激活码。@return false = 已在冻结中 */
    public boolean hold(long cardAssetId, String actor) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Card card = requireCard(cardAssetId);
            boolean held = cards.hold(card.id());
            audit.record(actor, "CARD_HOLD", "CARD", Long.toString(card.id()), null, null);
            return held;
        }));
    }

    public void release(long cardAssetId, String actor) {
        tx.executeWithoutResult(status -> {
            Card card = requireCard(cardAssetId);
            cards.release(card.id());
            audit.record(actor, "CARD_RELEASE", "CARD", Long.toString(card.id()), null, null);
        });
    }

    /** 渠道卡认领：属主从空写成会员，只能一次（与不可转号一致，DB 触发器兜底）。 */
    public void claimOwner(long cardAssetId, String memberId, String actor) {
        tx.executeWithoutResult(status -> {
            Card card = requireCard(cardAssetId);
            if (card.ownerMemberId() != null || !cards.bindOwner(card.id(), memberId)) {
                throw new UserFacingException("这张卡已被认领");
            }
            audit.record(actor, "CARD_CLAIM", "CARD", Long.toString(card.id()), null, "{\"owner\":\"" + memberId + "\"}");
        });
    }

    /** 请求上游改状态。终止按 终止 → 停用 → 本地终止 降级；停用/恢复不支持直接拒绝。 */
    public StatusRequest requestStatus(long cardAssetId, StatusOp op, String actor) {
        return tx.execute(status -> {
            Card card = requireCard(cardAssetId);
            if (!UPSTREAM_LIVE.contains(card.status())) {
                throw new UserFacingException("这张卡当前状态不能" + opText(op));
            }
            SupplierCapabilities caps = adapters.require(suppliers.find(card.supplierId()).orElseThrow()).capabilities();
            Instant now = clock.instant();
            StatusRequest result;
            if (caps.statusOps().contains(op)) {
                enqueue(card, CommandType.valueOf(op.name()), now);
                result = StatusRequest.QUEUED;
            } else if (op != StatusOp.TERMINATE) {
                throw new UserFacingException("该供应商不支持" + opText(op));
            } else if (caps.statusOps().contains(StatusOp.SUSPEND)) {
                enqueue(card, CommandType.SUSPEND, now);
                tasks.open(ManualTasks.Kind.RECON_DIFF, "CARD", card.id(), "供应商不支持终止，已改为停用，请与供应商核对计费", now);
                result = StatusRequest.DEGRADED_TO_SUSPEND;
            } else {
                cards.move(card, CardAssetStatus.TERMINATED, now);
                cards.schedulePoll(card.id(), null, null);
                tasks.open(ManualTasks.Kind.RECON_DIFF, "CARD", card.id(), "供应商不支持停用与终止，仅本地标记终止，请与供应商核对计费", now);
                result = StatusRequest.LOCAL_ONLY;
            }
            audit.record(actor, "CARD_STATUS_REQUEST", "CARD", Long.toString(card.id()), null,
                    "{\"op\":\"" + op + "\",\"result\":\"" + result + "\"}");
            return result;
        });
    }

    /** 取消这张卡（或其某个套餐）未在执行中的指令；轮询查询不动。@return 取消的条数 */
    public int cancelPending(long cardAssetId, Long cardPlanId, String actor) {
        return tx.execute(status -> {
            Card card = requireCard(cardAssetId);
            int cancelled = commands.cancelOpen(card.id(), cardPlanId, clock.instant());
            audit.record(actor, "CARD_CANCEL_PENDING", "CARD", Long.toString(card.id()), null,
                    "{\"plan\":" + cardPlanId + ",\"cancelled\":" + cancelled + "}");
            return cancelled;
        });
    }

    /**
     * 整卡退款收尾：取消未执行指令，卡作废（未交付）或终止（已开通），未生效的套餐取消，池卡归还或作废，解除冻结、停止轮询。
     * 上游侧的终止由调用方先经 {@link #requestStatus} 完成。
     */
    public void finalizeRefund(long cardAssetId, RefundEnd end, PoolDisposition poolDisposition, String actor) {
        tx.executeWithoutResult(status -> {
            Card card = requireCard(cardAssetId);
            Instant now = clock.instant();
            CardAssetStatus target = CardAssetStatus.valueOf(end.name());
            if (card.status() != target && !CardAssetStatus.MACHINE.canMove(card.status(), target)) {
                throw new UserFacingException("这张卡当前状态（" + CardViewProjector.statusText(card.status()) + "）不能"
                        + (end == RefundEnd.CANCELLED ? "作废" : "终止"));
            }
            commands.cancelOpen(card.id(), null, now);
            if (card.status() != target) {
                cards.move(card, target, now);
            }
            for (Plan plan : plans.byCard(card.id())) {
                if (CardPlanStatus.MACHINE.canMove(plan.status(), CardPlanStatus.CANCELLED)) {
                    plans.move(plan, CardPlanStatus.CANCELLED);
                }
            }
            if (pool.allocatedTo(card.id()).isPresent()) {
                if (poolDisposition == PoolDisposition.RETURN) {
                    pool.returnFor(card.id());
                    cards.releaseIccidHash(card.id());
                } else {
                    pool.voidFor(card.id());
                }
            }
            cards.release(card.id());
            cards.schedulePoll(card.id(), null, null);
            audit.record(actor, "CARD_REFUND_FINALIZE", "CARD", Long.toString(card.id()), "{\"status\":\"" + card.status() + "\"}",
                    "{\"status\":\"" + target + "\",\"pool\":\"" + poolDisposition + "\"}");
        });
    }

    /** 套餐退款收尾：取消该套餐未执行的指令，未生效的套餐取消；已就绪/在用的不能撤销，转人工。@return true = 已取消 */
    public boolean finalizePlanRefund(long cardPlanId, String actor) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Plan plan = plans.find(cardPlanId).orElseThrow(() -> new UserFacingException("找不到这个套餐"));
            Instant now = clock.instant();
            commands.cancelOpen(plan.cardAssetId(), plan.id(), now);
            boolean cancelled = CardPlanStatus.MACHINE.canMove(plan.status(), CardPlanStatus.CANCELLED);
            if (cancelled) {
                plans.move(plan, CardPlanStatus.CANCELLED);
            } else {
                tasks.open(ManualTasks.Kind.REFUND_EXCEPTION, "CARD_PLAN", plan.id(), "套餐已是" + CardViewProjector.planStatusText(plan.status())
                        + "，无法自动撤销，请人工与供应商处理", now);
            }
            audit.record(actor, "PLAN_REFUND_FINALIZE", "CARD_PLAN", Long.toString(plan.id()), null, "{\"cancelled\":" + cancelled + "}");
            return cancelled;
        }));
    }

    /**
     * 退款完成后推进履约行（§7.2）：加包退款 → 该加包行 CANCELLED；整卡退款 → 行内卡都已作废/终止则 CANCELLED，否则 PARTIAL_REFUNDED。
     * 已在目标状态或状态机不允许时不动。@return 行的当前状态
     */
    public FulfillmentLineStatus settleRefundedLine(long cardAssetId, Long cardPlanId, String actor) {
        return tx.execute(status -> {
            long lineId;
            FulfillmentLineStatus target;
            if (cardPlanId != null) {
                lineId = plans.find(cardPlanId).orElseThrow(() -> new UserFacingException("找不到这个套餐")).fulfillmentLineId();
                target = FulfillmentLineStatus.CANCELLED;
            } else {
                lineId = requireCard(cardAssetId).fulfillmentLineId();
                target = cards.byLine(lineId).stream().allMatch(c -> REFUND_ENDS.contains(c.status()))
                        ? FulfillmentLineStatus.CANCELLED : FulfillmentLineStatus.PARTIAL_REFUNDED;
            }
            FulfillmentLineStatus current = lines.lock(lineId).orElseThrow().status();
            if (current == target || !FulfillmentLineStatus.MACHINE.canMove(current, target)) {
                return current;
            }
            lines.move(lineId, current, target);
            audit.record(actor, "LINE_REFUND_SETTLE", "FULFILLMENT_LINE", Long.toString(lineId), "{\"status\":\"" + current + "\"}",
                    "{\"status\":\"" + target + "\"}");
            return target;
        });
    }

    private void enqueue(Card card, CommandType type, Instant now) {
        commands.enqueue(type, card.supplierId(), card.id(), null, CommandPriority.WRITE,
                settings.get(FulfillmentSettings.COMMAND_MAX_ATTEMPTS, SettingScope.supplier(card.supplierId())), now);
    }

    private Card requireCard(long cardAssetId) {
        return cards.lock(cardAssetId).orElseThrow(() -> new UserFacingException("找不到这张卡"));
    }

    private static String opText(StatusOp op) {
        return switch (op) {
            case SUSPEND -> "停用";
            case RESUME -> "恢复";
            case TERMINATE -> "终止";
        };
    }
}
