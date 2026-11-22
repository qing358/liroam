package com.liroam.fulfillment.delivery;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.CardPlanStatus;
import com.liroam.fulfillment.state.FulfillmentLineStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.CardPlans;
import com.liroam.fulfillment.store.CardPlans.Plan;
import com.liroam.fulfillment.store.FulfillmentLines;
import com.liroam.fulfillment.store.FulfillmentLines.Line;

/**
 * 履约行推进（domain-design §7.2）：新卡行全部卡已交码 → COMPLETED；有卡进 EXCEPTION → PARTIAL；PARTIAL 的异常卡后来交码 → COMPLETED。
 * 加包行：套餐就绪 → COMPLETED，开通失败 → PARTIAL。行完成且整张壳订单（同 {@code source_ref} 的 SHOP/TOPUP 行）都完成 → 记待回写发货。
 * 事务内调用。
 */
public class LineProgress {

    private static final Set<CardAssetStatus> DELIVERED = EnumSet.of(CardAssetStatus.DELIVERED, CardAssetStatus.IN_USE, CardAssetStatus.SUSPENDED,
            CardAssetStatus.EXPIRED, CardAssetStatus.TERMINATED);
    private static final Set<CardPlanStatus> PLAN_DONE = EnumSet.of(CardPlanStatus.READY, CardPlanStatus.IN_USE, CardPlanStatus.DEPLETED,
            CardPlanStatus.EXPIRED);

    private final FulfillmentLines lines;
    private final CardAssets cards;
    private final CardPlans plans;
    private final ShellDeliveries shellDeliveries;

    public LineProgress(FulfillmentLines lines, CardAssets cards, CardPlans plans, ShellDeliveries shellDeliveries) {
        this.lines = lines;
        this.cards = cards;
        this.plans = plans;
        this.shellDeliveries = shellDeliveries;
    }

    public void refresh(long lineId) {
        Line line = lines.lock(lineId).orElseThrow();
        FulfillmentLineStatus current = line.status();
        if (current != FulfillmentLineStatus.IN_PROGRESS && current != FulfillmentLineStatus.PARTIAL) {
            return;
        }
        FulfillmentLineStatus target = line.sourceType() == SourceType.TOPUP ? topUpTarget(lineId) : newCardTarget(lineId);
        if (target == null || target == current || !FulfillmentLineStatus.MACHINE.canMove(current, target)) {
            return;
        }
        lines.move(lineId, current, target);
        if (target == FulfillmentLineStatus.COMPLETED && line.sourceType() != SourceType.CHANNEL && orderCompleted(line.sourceRef())) {
            shellDeliveries.enqueue(line.sourceRef());
        }
    }

    private FulfillmentLineStatus newCardTarget(long lineId) {
        List<Card> lineCards = cards.byLine(lineId);
        if (!lineCards.isEmpty() && lineCards.stream().allMatch(c -> DELIVERED.contains(c.status()))) {
            return FulfillmentLineStatus.COMPLETED;
        }
        return lineCards.stream().anyMatch(c -> c.status() == CardAssetStatus.EXCEPTION) ? FulfillmentLineStatus.PARTIAL : null;
    }

    private FulfillmentLineStatus topUpTarget(long lineId) {
        List<Plan> linePlans = plans.byLine(lineId);
        if (!linePlans.isEmpty() && linePlans.stream().allMatch(p -> PLAN_DONE.contains(p.status()))) {
            return FulfillmentLineStatus.COMPLETED;
        }
        return linePlans.stream().anyMatch(p -> p.status() == CardPlanStatus.FAILED) ? FulfillmentLineStatus.PARTIAL : null;
    }

    private boolean orderCompleted(String sourceRef) {
        return lines.shellOrderLines(sourceRef).stream().allMatch(l -> l.status() == FulfillmentLineStatus.COMPLETED);
    }
}
