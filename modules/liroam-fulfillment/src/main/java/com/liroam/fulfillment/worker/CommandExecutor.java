package com.liroam.fulfillment.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.SupplierAdapterRegistry;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.credential.CredentialDeliveries;
import com.liroam.fulfillment.delivery.LineProgress;
import com.liroam.fulfillment.poll.PollTiers;
import com.liroam.fulfillment.poll.UsageMirror;
import com.liroam.fulfillment.pool.CardPool;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.CardPlanStatus;
import com.liroam.fulfillment.state.CommandPriority;
import com.liroam.fulfillment.state.CommandStatus;
import com.liroam.fulfillment.state.CommandType;
import com.liroam.fulfillment.store.CallLogs;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.CardPlans;
import com.liroam.fulfillment.store.CardPlans.Plan;
import com.liroam.fulfillment.store.Offerings;
import com.liroam.fulfillment.store.Offerings.Offering;
import com.liroam.fulfillment.store.PoolItems.Item;
import com.liroam.fulfillment.store.SupplierCommands;
import com.liroam.fulfillment.store.SupplierCommands.Command;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.fulfillment.worker.SupplierRateLimiter.Permit;
import com.liroam.fulfillment.worker.SupplierRateLimiter.Purpose;
import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.LookupQuery;
import com.liroam.supplier.spi.LookupResult;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.StatusCommand;
import com.liroam.supplier.spi.StatusResult;
import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.SupplierCapabilities;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.TopUpResult;
import com.liroam.supplier.spi.UsageSnapshot;

/**
 * 指令执行 worker（domain-design §6.3）。
 * <ol>
 *   <li>事务内 {@code FOR UPDATE SKIP LOCKED} 认领一批（写 {@code locked_by/locked_until} 租约），崩溃者的租约过期后被回收；回收的写指令先查档。</li>
 *   <li>每条：退款冻结中的开通类指令推迟；本地分配（ALLOCATE）不调上游；其余先取供应商令牌，取不到推迟、不计失败。</li>
 *   <li>事务外调适配器，每次调用写一行 {@code lr_supplier_call_log}（脱敏）。</li>
 *   <li>结果落库前确认租约仍在本 worker 手里：Ok 推进台账；Retryable 退避重试（上游限流不计次数）；Rejected 转人工 + 待办；
 *       写操作 Unknown 转 LOOKUP_PENDING，下一轮查档（声明幂等键的供应商直接带同一 {@code request_no} 重发）。</li>
 * </ol>
 * 次数与退避取 {@code command.max_attempts / backoff_base_s / backoff_max_s}（供应商可覆盖）。
 */
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    /** 认领租约：一次调用加落库远小于它；过期即视为持有者崩溃。 */
    public static final Duration LEASE = Duration.ofMinutes(5);
    static final String API_OPERATOR = "system:api";

    private final TransactionOperations tx;
    private final SupplierCommands commands;
    private final CardAssets cards;
    private final CardPlans plans;
    private final Offerings offerings;
    private final SupplierDirectory suppliers;
    private final SupplierAdapterRegistry adapters;
    private final SupplierRateLimiter limiter;
    private final CardPool pool;
    private final CredentialDeliveries deliveries;
    private final LineProgress lineProgress;
    private final UsageMirror usageMirror;
    private final PollTiers pollTiers;
    private final ManualTasks tasks;
    private final CallLogs callLogs;
    private final Settings settings;
    private final SensitiveCipher cipher;
    private final Clock clock;

    public CommandExecutor(TransactionOperations tx, SupplierCommands commands, CardAssets cards, CardPlans plans, Offerings offerings,
            SupplierDirectory suppliers, SupplierAdapterRegistry adapters, SupplierRateLimiter limiter, CardPool pool,
            CredentialDeliveries deliveries, LineProgress lineProgress, UsageMirror usageMirror, PollTiers pollTiers, ManualTasks tasks,
            CallLogs callLogs, Settings settings, SensitiveCipher cipher, Clock clock) {
        this.tx = tx;
        this.commands = commands;
        this.cards = cards;
        this.plans = plans;
        this.offerings = offerings;
        this.suppliers = suppliers;
        this.adapters = adapters;
        this.limiter = limiter;
        this.pool = pool;
        this.deliveries = deliveries;
        this.lineProgress = lineProgress;
        this.usageMirror = usageMirror;
        this.pollTiers = pollTiers;
        this.tasks = tasks;
        this.callLogs = callLogs;
        this.settings = settings;
        this.cipher = cipher;
        this.clock = clock;
    }

    /** @return 本轮认领并处理的指令数 */
    public int runOnce(int batch, String workerId) {
        Instant now = clock.instant();
        List<Command> claimed = tx.execute(status -> commands.claim(batch, workerId, now, now.plus(LEASE)));
        for (Command c : claimed) {
            try {
                execute(c, workerId);
            } catch (RuntimeException e) {
                log.error("指令[{}] {} 执行出错", c.requestNo(), c.type(), e);
                settle(c, workerId, () -> failure(c, "INTERNAL", "INTERNAL", e.toString()));
            }
        }
        return claimed.size();
    }

    private void execute(Command c, String workerId) {
        Card card = cards.find(c.cardAssetId()).orElseThrow();
        com.liroam.catalog.Supplier supplier = suppliers.find(c.supplierId()).orElseThrow();
        SupplierAdapter adapter = adapters.require(supplier);
        SupplierCapabilities caps = adapter.capabilities();
        if (card.refundHold() && c.type().heldByRefund()) {
            settle(c, workerId, () -> commands.postpone(c, "HELD", "REFUND_HOLD", "卡在退款冻结中，暂不执行", later(c, 1), clock.instant()));
            return;
        }
        if (c.type() == CommandType.ALLOCATE) {
            settle(c, workerId, () -> allocate(c));
            return;
        }
        int capacity = SupplierRateLimiter.capacity(supplier, caps.rateLimitPerMinute());
        Permit permit = limiter.acquire(supplier, capacity, c.type() == CommandType.QUERY ? Purpose.READ : Purpose.WRITE);
        if (!permit.granted()) {
            settle(c, workerId, () -> commands.postpone(c, "THROTTLED", "RATE_LIMIT", "本地令牌桶已空", clock.instant().plus(permit.retryAfter()),
                    clock.instant()));
            return;
        }
        boolean lookup = c.status() == CommandStatus.LOOKUP_PENDING && !caps.idempotencyKey() && c.type().lookupOperation().isPresent();
        CardRef ref = cardRef(card);
        Outcome<?> outcome = lookup
                ? call(c, "LOOKUP_" + c.type(), ref, () -> adapter.lookup(new LookupQuery(c.requestNo(), c.type().lookupOperation().get(), ref)))
                : call(c, c.type().name(), ref, () -> invoke(adapter, c, card, ref));
        settle(c, workerId, () -> handle(c, lookup, outcome, caps));
    }

    private Outcome<?> invoke(SupplierAdapter adapter, Command c, Card card, CardRef ref) {
        return switch (c.type()) {
            case PROVISION -> {
                Offering offering = offerings.get(plan(c).offeringId());
                yield adapter.provision(new ProvisionCommand(c.requestNo(), card.id(), ref.iccid(), offering.code(), offering.payload()));
            }
            case FETCH_CREDENTIAL -> adapter.fetchCredential(ref);
            case ADD_PLAN -> {
                Offering offering = offerings.get(plan(c).offeringId());
                yield adapter.addPlan(new TopUpCommand(c.requestNo(), ref, offering.code(), offering.payload()));
            }
            case SUSPEND -> adapter.changeStatus(new StatusCommand(c.requestNo(), ref, StatusOp.SUSPEND));
            case RESUME -> adapter.changeStatus(new StatusCommand(c.requestNo(), ref, StatusOp.RESUME));
            case TERMINATE -> adapter.changeStatus(new StatusCommand(c.requestNo(), ref, StatusOp.TERMINATE));
            case QUERY -> adapter.queryUsage(ref);
            case ALLOCATE -> throw new IllegalStateException("ALLOCATE 不调上游");
        };
    }

    private Outcome<?> call(Command c, String operation, CardRef ref, Supplier<Outcome<?>> upstream) {
        long started = System.nanoTime();
        Outcome<?> outcome;
        try {
            outcome = upstream.get();
        } catch (RuntimeException e) {
            log.warn("指令[{}] 适配器抛出异常，按结果未知处理", c.requestNo(), e);
            outcome = Outcome.unknown("ADAPTER_EXCEPTION " + e.getClass().getSimpleName());
        }
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String request = "requestNo=" + c.requestNo() + " card=" + ref.cardAssetId() + " iccid=" + (ref.iccid() == null ? "-" : "****"
                + SensitiveCipher.last4(ref.iccid())) + " upstreamRef=" + ref.upstreamRef();
        callLogs.record(c.id(), c.supplierId(), operation, latencyMs, request, redact(outcome), outcomeName(outcome));
        return outcome;
    }

    /** 落库前确认租约仍在本 worker 手里；被他人回收则丢弃本次结果（对方会查档或重发）。 */
    private void settle(Command c, String workerId, Runnable work) {
        tx.executeWithoutResult(status -> {
            if (!commands.stillHeld(c.id(), workerId)) {
                log.warn("指令[{}] 租约已被回收，丢弃本次结果", c.requestNo());
                return;
            }
            work.run();
        });
    }

    private void handle(Command c, boolean lookup, Outcome<?> outcome, SupplierCapabilities caps) {
        Instant now = clock.instant();
        switch (outcome) {
            case Outcome.Ok<?> ok when lookup -> {
                LookupResult result = (LookupResult) ok.value();
                if (result.effect() == LookupResult.Effect.EFFECTIVE) {
                    succeed(c, result.upstreamRef(), null, caps);
                } else {
                    commands.retryLater(c, false, "LOOKUP", "NOT_EFFECTIVE", "查档确认未生效，重发", now, now);
                }
            }
            case Outcome.Ok<?> ok -> succeed(c, null, ok.value(), caps);
            case Outcome.Retryable<?> r when r.retryAfter() != null && !lookup ->
                    commands.retryLater(c, false, "THROTTLED", "UPSTREAM_THROTTLED", r.reason(), now.plus(r.retryAfter()), now);
            case Outcome.Retryable<?> r when lookup -> lookupAgain(c, "RETRYABLE", r.reason());
            case Outcome.Retryable<?> r -> failure(c, "RETRYABLE", r.reason(), r.reason());
            case Outcome.Rejected<?> r -> giveUp(c, "REJECTED", r.code(), r.message());
            case Outcome.Unknown<?> u when lookup -> lookupAgain(c, "UNKNOWN", u.reason());
            case Outcome.Unknown<?> u when c.type().lookupOperation().isPresent() -> {
                if (c.attempts() + 1 >= c.maxAttempts()) {
                    giveUp(c, "UNKNOWN", "UNKNOWN", u.reason());
                } else {
                    commands.lookupPending(c, u.reason(), now.plus(backoff(c, c.attempts() + 1)), now);
                }
            }
            case Outcome.Unknown<?> u -> failure(c, "UNKNOWN", "UNKNOWN", u.reason());
        }
    }

    private void lookupAgain(Command c, String errorClass, String reason) {
        if (c.attempts() + 1 >= c.maxAttempts()) {
            giveUp(c, errorClass, "LOOKUP_FAILED", "查档仍无法确认：" + reason);
            return;
        }
        Instant now = clock.instant();
        commands.lookupPending(c, reason, now.plus(backoff(c, c.attempts() + 1)), now);
    }

    /** 计一次失败：到顶转人工，否则退避重试。 */
    private void failure(Command c, String errorClass, String code, String message) {
        if (c.attempts() + 1 >= c.maxAttempts()) {
            giveUp(c, errorClass, code, message);
            return;
        }
        Instant now = clock.instant();
        commands.retryLater(c, true, errorClass, code, message, now.plus(backoff(c, c.attempts() + 1)), now);
    }

    /** 不再自动重试：转 MANUAL + 待办，开通类把卡/套餐标异常并推进履约行。轮询查询直接放弃本次、按分层再排。 */
    private void giveUp(Command c, String errorClass, String code, String message) {
        Instant now = clock.instant();
        if (c.type() == CommandType.QUERY) {
            commands.abandon(c, errorClass, code, message, now);
            pollTiers.reschedule(cards.find(c.cardAssetId()).orElseThrow(), now);
            return;
        }
        commands.manual(c, true, errorClass, code, message, now);
        tasks.open(ManualTasks.Kind.COMMAND_FAILED, "SUPPLIER_COMMAND", c.id(), c.type() + " 失败：" + message, now);
        switch (c.type()) {
            case ALLOCATE, PROVISION, FETCH_CREDENTIAL -> {
                Card card = cards.lock(c.cardAssetId()).orElseThrow();
                if (CardAssetStatus.MACHINE.canMove(card.status(), CardAssetStatus.EXCEPTION)) {
                    cards.move(card, CardAssetStatus.EXCEPTION, now);
                }
                lineProgress.refresh(card.fulfillmentLineId());
            }
            case ADD_PLAN -> {
                Plan plan = plan(c);
                if (CardPlanStatus.MACHINE.canMove(plan.status(), CardPlanStatus.FAILED)) {
                    plans.move(plan, CardPlanStatus.FAILED);
                }
                lineProgress.refresh(plan.fulfillmentLineId());
            }
            default -> {
            }
        }
    }

    private void allocate(Command c) {
        Instant now = clock.instant();
        Card card = cards.lock(c.cardAssetId()).orElseThrow();
        if (card.status() != CardAssetStatus.CREATED) {
            commands.succeed(c, null, now);
            return;
        }
        Optional<Item> item = pool.allocate(card);
        if (item.isEmpty()) {
            tasks.open(ManualTasks.Kind.POOL_EMPTY, "CARD_PRODUCT", card.cardProductId(), "卡池没有可分配的卡（供应商 " + card.supplierId() + "，形态 "
                    + card.form() + "），请导入卡号", now);
            commands.postpone(c, "RETRYABLE", "POOL_EMPTY", "卡池无可分配卡", later(c, 1), now);
            return;
        }
        cards.setIccid(card.id(), item.get().sealedIccid(), item.get().iccidHash(), item.get().iccidLast4());
        cards.move(card, CardAssetStatus.ALLOCATED, now);
        commands.succeed(c, null, now);
        commands.enqueue(CommandType.PROVISION, c.supplierId(), card.id(), c.cardPlanId(), CommandPriority.WRITE, maxAttempts(c), now);
    }

    private void succeed(Command c, String lookedUpRef, Object value, SupplierCapabilities caps) {
        Instant now = clock.instant();
        switch (c.type()) {
            case PROVISION -> provisioned(c, value == null ? new ProvisionResult(null, lookedUpRef, Optional.empty()) : (ProvisionResult) value, caps);
            case FETCH_CREDENTIAL -> {
                CredentialResult credential = (CredentialResult) value;
                commands.succeed(c, null, now);
                deliveries.deliver(c.cardAssetId(), credential.kind(), credential.payload(), CredentialChannel.API, API_OPERATOR);
            }
            case ADD_PLAN -> {
                String planRef = value == null ? lookedUpRef : ((TopUpResult) value).upstreamPlanRef();
                Plan plan = plan(c);
                plans.ready(plan, planRef);
                commands.succeed(c, planRef, now);
                lineProgress.refresh(plan.fulfillmentLineId());
                Card card = cards.find(c.cardAssetId()).orElseThrow();
                if (PollTiers.pollable(card.status())) {
                    cards.schedulePoll(card.id(), card.pollTier(), now);
                }
            }
            case SUSPEND, RESUME, TERMINATE -> {
                statusChanged(c, value == null ? null : ((StatusResult) value).status());
                commands.succeed(c, lookedUpRef, now);
            }
            case QUERY -> {
                usageMirror.apply(c.cardAssetId(), (UsageSnapshot) value, now);
                commands.succeed(c, null, now);
            }
            case ALLOCATE -> throw new IllegalStateException("ALLOCATE 不调上游");
        }
    }

    private void provisioned(Command c, ProvisionResult result, SupplierCapabilities caps) {
        Instant now = clock.instant();
        Card card = cards.lock(c.cardAssetId()).orElseThrow();
        commands.succeed(c, result.upstreamRef(), now);
        if (card.status() == CardAssetStatus.CREATED) {
            cards.move(card, CardAssetStatus.ALLOCATED, now);
            card = cards.find(card.id()).orElseThrow();
        }
        if (card.status() != CardAssetStatus.ALLOCATED) {
            tasks.open(ManualTasks.Kind.RECON_DIFF, "CARD", card.id(), "上游已开通，但本地卡已是 " + card.status() + "，请与供应商核对", now);
            return;
        }
        if (card.iccidCipher() == null && result.iccid() != null) {
            String iccid = SensitiveCipher.normalizeIccid(result.iccid());
            cards.setIccid(card.id(), cipher.seal(SensitiveField.ICCID, iccid), cipher.hash(SensitiveField.ICCID, iccid), SensitiveCipher.last4(iccid));
        }
        if (result.upstreamRef() != null) {
            cards.setUpstreamRef(card.id(), result.upstreamRef());
        }
        cards.move(card, CardAssetStatus.PROVISIONED, now);
        plans.ready(plan(c), null);
        if (result.credential().isPresent()) {
            CredentialResult credential = result.credential().get();
            deliveries.deliver(card.id(), credential.kind(), credential.payload(), CredentialChannel.API, API_OPERATOR);
        } else if (deliveries.announceExisting(card.id())) {
            return;
        } else if (pool.allocatedTo(card.id()).flatMap(Item::sealedCredential).isPresent()) {
            deliveries.deliverSealed(card.id(), CredentialResult.Kind.LPA, pool.allocatedTo(card.id()).flatMap(Item::sealedCredential).get(),
                    CredentialChannel.IMPORT, null);
        } else if (caps.credentialChannels().contains(CredentialChannel.API)) {
            commands.enqueue(CommandType.FETCH_CREDENTIAL, c.supplierId(), card.id(), null, CommandPriority.WRITE, maxAttempts(c), now);
        }
    }

    private void statusChanged(Command c, UsageSnapshot.CardStatus reported) {
        Instant now = clock.instant();
        Card card = cards.lock(c.cardAssetId()).orElseThrow();
        CardAssetStatus target = switch (c.type()) {
            case SUSPEND -> CardAssetStatus.SUSPENDED;
            case RESUME -> card.activatedAt() != null ? CardAssetStatus.IN_USE : CardAssetStatus.DELIVERED;
            default -> CardAssetStatus.TERMINATED;
        };
        if (card.status() != target && CardAssetStatus.MACHINE.canMove(card.status(), target)) {
            cards.move(card, target, now);
        } else if (card.status() != target) {
            log.warn("卡[{}] {} 已在上游生效（上游状态 {}），本地 {} 不能迁到 {}", card.id(), c.type(), reported, card.status(), target);
        }
        pollTiers.reschedule(cards.find(card.id()).orElseThrow(), now);
    }

    private CardRef cardRef(Card card) {
        String iccid = card.sealedIccid().map(s -> cipher.open(SensitiveField.ICCID, s)).orElse(null);
        return new CardRef(card.id(), iccid, card.upstreamRef());
    }

    private Plan plan(Command c) {
        return plans.find(c.cardPlanId()).orElseThrow(() -> new IllegalStateException("指令 " + c.requestNo() + " 缺套餐"));
    }

    private Duration backoff(Command c, int failures) {
        SettingScope scope = SettingScope.supplier(c.supplierId());
        return Backoff.delay(failures, settings.get(FulfillmentSettings.COMMAND_BACKOFF_BASE_S, scope),
                settings.get(FulfillmentSettings.COMMAND_BACKOFF_MAX_S, scope));
    }

    /** 不计失败的推迟：按退避基数等一轮。 */
    private Instant later(Command c, int rounds) {
        return clock.instant().plus(backoff(c, rounds));
    }

    private int maxAttempts(Command c) {
        return settings.get(FulfillmentSettings.COMMAND_MAX_ATTEMPTS, SettingScope.supplier(c.supplierId()));
    }

    private static String outcomeName(Outcome<?> outcome) {
        return switch (outcome) {
            case Outcome.Ok<?> ok -> "OK";
            case Outcome.Retryable<?> r -> r.retryAfter() != null ? "THROTTLED" : "RETRYABLE";
            case Outcome.Rejected<?> r -> "REJECTED";
            case Outcome.Unknown<?> u -> "UNKNOWN";
        };
    }

    /** 响应摘要：不落 ICCID 全号与交码内容。 */
    private static String redact(Outcome<?> outcome) {
        if (!(outcome instanceof Outcome.Ok<?> ok)) {
            return outcome.toString();
        }
        return switch (ok.value()) {
            case ProvisionResult p -> "upstreamRef=" + p.upstreamRef() + " iccid=" + (p.iccid() == null ? "-" : "****"
                    + SensitiveCipher.last4(SensitiveCipher.normalizeIccid(p.iccid()))) + " credential=" + (p.credential().isPresent() ? "***" : "-");
            case UsageSnapshot u -> "status=" + u.status() + " plans=" + u.plans().size();
            default -> ok.value().toString();
        };
    }
}
