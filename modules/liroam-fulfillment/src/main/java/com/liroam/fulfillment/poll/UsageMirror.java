package com.liroam.fulfillment.poll;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.CardPlanStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.CardPlans;
import com.liroam.fulfillment.store.CardPlans.Plan;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;

/**
 * 把一次用量快照镜像进台账（domain-design §6.4）。事务内调用。
 * <ul>
 *   <li>套餐按 {@code upstream_plan_ref} 对上；首购套餐开通时没有上游套餐号，取第一条没对上的上游套餐回填。</li>
 *   <li>套餐：READY 有用量或已起算 → IN_USE；READY 未用但已过期 → EXPIRED（过激活期限）；IN_USE 过期 → EXPIRED、余量为 0 → DEPLETED。</li>
 *   <li>卡：上游停用/终止/到期照搬；否则有套餐在用 → IN_USE，全部套餐过期 → EXPIRED，恢复后回到 IN_USE 或 DELIVERED。</li>
 *   <li>卡 {@code expires_at} = 可用套餐（READY/IN_USE）的最晚到期，没有可用套餐时取全部套餐最晚到期。</li>
 * </ul>
 */
public class UsageMirror {

    private static final Set<CardPlanStatus> LIVE = EnumSet.of(CardPlanStatus.READY, CardPlanStatus.IN_USE);
    private static final Set<CardPlanStatus> ENDED = EnumSet.of(CardPlanStatus.EXPIRED, CardPlanStatus.DEPLETED);

    private final CardAssets cards;
    private final CardPlans plans;
    private final PollTiers tiers;

    public UsageMirror(CardAssets cards, CardPlans plans, PollTiers tiers) {
        this.cards = cards;
        this.plans = plans;
        this.tiers = tiers;
    }

    public void apply(long cardAssetId, UsageSnapshot snapshot, Instant now) {
        Card card = cards.lock(cardAssetId).orElseThrow();
        List<Plan> local = plans.byCard(card.id());
        List<PlanUsage> unmatched = new ArrayList<>(snapshot.plans());
        for (Plan plan : local) {
            if (plan.upstreamPlanRef() == null || !LIVE.contains(plan.status()) && !ENDED.contains(plan.status())) {
                continue;
            }
            unmatched.stream().filter(u -> plan.upstreamPlanRef().equals(u.upstreamPlanRef())).findFirst().ifPresent(u -> {
                unmatched.remove(u);
                mirrorPlan(plan, u, snapshot.rawJson(), now);
            });
        }
        for (Plan plan : local) {
            if (plan.upstreamPlanRef() == null && LIVE.contains(plan.status()) && !unmatched.isEmpty()) {
                mirrorPlan(plan, unmatched.removeFirst(), snapshot.rawJson(), now);
            }
        }
        List<Plan> after = plans.byCard(card.id());
        mirrorCard(card, snapshot.status(), after, now);
        Instant expiresAt = latestExpiry(after.stream().filter(p -> LIVE.contains(p.status())).toList())
                .or(() -> latestExpiry(after)).orElse(null);
        cards.mirrored(card.id(), now, expiresAt);
        tiers.reschedule(cards.find(card.id()).orElseThrow(), now);
    }

    private void mirrorPlan(Plan plan, PlanUsage usage, String raw, Instant now) {
        plans.mirror(plan.id(), usage, raw, now);
        boolean expired = usage.expiresAt() != null && !usage.expiresAt().isAfter(now);
        boolean used = usage.startsAt() != null || usage.usedBytes() != null && usage.usedBytes() > 0;
        CardPlanStatus status = plan.status();
        if (status == CardPlanStatus.READY) {
            if (used) {
                plans.move(plan, CardPlanStatus.IN_USE);
                status = CardPlanStatus.IN_USE;
                plan = plans.find(plan.id()).orElseThrow();
            } else if (expired) {
                plans.move(plan, CardPlanStatus.EXPIRED);
                return;
            }
        }
        if (status == CardPlanStatus.IN_USE) {
            if (expired) {
                plans.move(plan, CardPlanStatus.EXPIRED);
            } else if (usage.remainingBytes() != null && usage.remainingBytes() <= 0) {
                plans.move(plan, CardPlanStatus.DEPLETED);
            }
        }
    }

    private void mirrorCard(Card card, CardStatus upstream, List<Plan> cardPlans, Instant now) {
        if (!PollTiers.pollable(card.status())) {
            return;
        }
        List<Plan> counted = cardPlans.stream().filter(p -> LIVE.contains(p.status()) || ENDED.contains(p.status())).toList();
        CardAssetStatus target = switch (upstream) {
            case TERMINATED -> CardAssetStatus.TERMINATED;
            case SUSPENDED -> CardAssetStatus.SUSPENDED;
            case EXPIRED -> CardAssetStatus.EXPIRED;
            case ACTIVE, INACTIVE -> {
                if (counted.stream().anyMatch(p -> p.status() == CardPlanStatus.IN_USE)) {
                    yield CardAssetStatus.IN_USE;
                }
                if (!counted.isEmpty() && counted.stream().allMatch(p -> p.status() == CardPlanStatus.EXPIRED)) {
                    yield CardAssetStatus.EXPIRED;
                }
                if (card.status() == CardAssetStatus.SUSPENDED) {
                    yield card.activatedAt() != null ? CardAssetStatus.IN_USE : CardAssetStatus.DELIVERED;
                }
                yield card.status();
            }
        };
        if (target != card.status() && CardAssetStatus.MACHINE.canMove(card.status(), target)) {
            cards.move(card, target, now);
        }
    }

    private static Optional<Instant> latestExpiry(List<Plan> list) {
        return list.stream().map(Plan::expiresAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                .map(UtcColumns::fromColumn);
    }
}
