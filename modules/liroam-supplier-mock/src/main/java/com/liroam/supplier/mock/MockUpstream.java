package com.liroam.supplier.mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.liroam.supplier.mock.MockFaults.Failure;
import com.liroam.supplier.mock.MockFaults.Operation;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;

/**
 * 一家模拟上游：卡、套餐与写操作流水全在内存（ConcurrentHashMap；单卡变更经 {@code compute} 原子替换不可变快照）。
 * 对外只回上游原样的单位、时间串与状态码（{@link WireFormat}），归一由适配器完成。
 * <p>
 * 卡的 upstreamRef 形如 {@code MA-<开通时刻 epoch 秒，36 进制>-<随机串>-<套餐码>}：进程重启、内存丢失后，
 * 凭它重建「已开通未激活、额度满、到期 = 开通时刻 + 有效期」的初始状态；加包记录与用量不重建。
 */
final class MockUpstream {

    static final String OK = "OK";
    static final String THROTTLED = "THROTTLED";
    static final String SERVER_ERROR = "SERVER_ERROR";
    static final String BAD_REQUEST = "BAD_REQUEST";
    static final String CARD_NOT_FOUND = "CARD_NOT_FOUND";
    static final String ICCID_IN_USE = "ICCID_IN_USE";
    static final String CARD_TERMINATED = "CARD_TERMINATED";

    /** 连接没建立：请求未发出。 */
    static final class NotSent extends RuntimeException {
        NotSent() {
            super("连接未建立", null, false, false);
        }
    }

    /** 请求已发出、等不到响应：上游已执行。 */
    static final class TimedOutAfterSend extends RuntimeException {
        TimedOutAfterSend() {
            super("写后读超时", null, false, false);
        }
    }

    /** 上游业务拒绝（登记过的错误码）。 */
    private static final class Refused extends RuntimeException {
        final String code;

        Refused(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    record Reply<T>(String code, T body) {
        static <T> Reply<T> ok(T body) {
            return new Reply<>(OK, body);
        }

        static <T> Reply<T> error(String code) {
            return new Reply<>(code, null);
        }
    }

    record Plan(String ref, MockPackage pkg, long usedBytes, Instant startsAt, Instant expiresAt) {
    }

    enum Lifecycle { LIVE, SUSPENDED, TERMINATED }

    record Card(String ref, String iccid, Lifecycle lifecycle, boolean activated, List<Plan> plans) {
        Card {
            plans = List.copyOf(plans);
        }

        Plan base() {
            return plans.getFirst();
        }

        CardStatus status(Instant now) {
            return switch (lifecycle) {
                case TERMINATED -> CardStatus.TERMINATED;
                case SUSPENDED -> CardStatus.SUSPENDED;
                case LIVE -> plans.stream().noneMatch(p -> now.isBefore(p.expiresAt())) ? CardStatus.EXPIRED
                        : activated ? CardStatus.ACTIVE : CardStatus.INACTIVE;
            };
        }

        Card with(Lifecycle next) {
            return new Card(ref, iccid, next, activated, plans);
        }

        Card withPlans(boolean nowActivated, List<Plan> next) {
            return new Card(ref, iccid, lifecycle, nowActivated, next);
        }
    }

    /** 线路上的一个套餐：流量为上游单位，未计量的维度为空。 */
    record WirePlan(String ref, long quota, long used, Long voiceSeconds, Integer sms, String startsAt, String expiresAt) {
    }

    record WireCard(String ref, String status, List<WirePlan> plans, String json) {
    }

    private final String adapterKey;
    private final String refPrefix;
    private final WireFormat wire;
    private final boolean idempotencyKey;
    private final MockFaults faults;
    private final Clock clock;

    private final Map<String, Card> cards = new ConcurrentHashMap<>();
    private final Map<String, String> refByIccid = new ConcurrentHashMap<>();
    /** requestNo → 该写操作生效后的上游引用（卡或套餐），供查档。 */
    private final Map<String, String> journal = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> provisions = new ConcurrentHashMap<>();

    MockUpstream(String adapterKey, String refPrefix, WireFormat wire, boolean idempotencyKey, MockFaults faults, Clock clock) {
        this.adapterKey = adapterKey;
        this.refPrefix = refPrefix;
        this.wire = wire;
        this.idempotencyKey = idempotencyKey;
        this.faults = faults;
        this.clock = clock;
    }

    String adapterKey() {
        return adapterKey;
    }

    WireFormat wire() {
        return wire;
    }

    private <T> Reply<T> call(Operation op, String requestNo, String iccid, String offeringCode, Supplier<Reply<T>> effect) {
        Failure failure = faults.take(adapterKey, op, requestNo, iccid, offeringCode).orElse(null);
        if (failure == null) {
            return run(effect);
        }
        return switch (failure) {
            case CONNECT_FAILED -> throw new NotSent();
            case THROTTLED -> Reply.error(THROTTLED);
            case SERVER_ERROR -> Reply.error(SERVER_ERROR);
            case BAD_REQUEST -> Reply.error(BAD_REQUEST);
            case UNREGISTERED_CODE -> Reply.error("E" + ThreadLocalRandom.current().nextInt(1000, 10000));
            case TIMEOUT_AFTER_SEND -> {
                run(effect);
                throw new TimedOutAfterSend();
            }
        };
    }

    private static <T> Reply<T> run(Supplier<Reply<T>> effect) {
        try {
            return effect.get();
        } catch (Refused e) {
            return Reply.error(e.code);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private String newRef(String kind, Instant at, String offeringCode) {
        return refPrefix + kind + "-" + Long.toString(at.getEpochSecond(), 36) + "-"
                + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36, 36 * 36 * 36 * 36), 36) + "-" + offeringCode;
    }

    private static Plan plan(String ref, MockPackage pkg, Instant startsAt) {
        return new Plan(ref, pkg, 0, startsAt, startsAt.plus(Duration.ofDays(pkg.days())));
    }

    private Card newCard(String iccid, MockPackage pkg) {
        Instant at = now();
        String ref = newRef("", at, pkg.code());
        String cardIccid = iccid != null ? iccid : "8999" + String.format("%015d", ThreadLocalRandom.current().nextLong(1_000_000_000_000_000L));
        return new Card(ref, cardIccid, Lifecycle.LIVE, false, List.of(plan(ref, pkg, at)));
    }

    /** 内存里的卡；内存没有时按 upstreamRef 重建，引用不是本上游发的则为 null。 */
    private Card card(String ref) {
        return ref == null ? null : cards.computeIfAbsent(ref, this::rebuild);
    }

    private Card rebuild(String ref) {
        String head = refPrefix + "-";
        if (!ref.startsWith(head)) {
            return null;
        }
        String[] parts = ref.substring(head.length()).split("-", 3);
        if (parts.length != 3) {
            return null;
        }
        Instant at;
        try {
            at = Instant.ofEpochSecond(Long.parseLong(parts[0], 36));
        } catch (NumberFormatException e) {
            return null;
        }
        return MockPackage.parse(parts[2]).map(pkg -> new Card(ref, null, Lifecycle.LIVE, false, List.of(plan(ref, pkg, at)))).orElse(null);
    }

    private Card mutate(String ref, UnaryOperator<Card> change) {
        if (card(ref) == null) {
            throw new Refused(CARD_NOT_FOUND);
        }
        return cards.compute(ref, (k, c) -> change.apply(c));
    }

    private void recordProvision(String requestNo, Card card) {
        cards.put(card.ref(), card);
        refByIccid.putIfAbsent(card.iccid(), card.ref());
        provisions.computeIfAbsent(requestNo, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 开卡。池模式带 ICCID；下单即建传 null，由上游发号。有幂等键的上游对同一 requestNo 只开一次。 */
    Reply<Card> provision(String requestNo, String iccid, MockPackage pkg) {
        return call(Operation.PROVISION, requestNo, iccid, pkg.code(), () -> {
            if (idempotencyKey) {
                String ref = journal.computeIfAbsent(requestNo, k -> {
                    Card card = newCard(iccid, pkg);
                    recordProvision(requestNo, card);
                    return card.ref();
                });
                return Reply.ok(cards.get(ref));
            }
            Card card = newCard(iccid, pkg);
            if (refByIccid.putIfAbsent(iccid, card.ref()) != null) {
                throw new Refused(ICCID_IN_USE);
            }
            recordProvision(requestNo, card);
            journal.put(requestNo, card.ref());
            return Reply.ok(card);
        });
    }

    Reply<WireCard> query(String ref, String iccid) {
        return call(Operation.QUERY, null, iccid, null, () -> {
            Card card = card(ref);
            if (card == null) {
                throw new Refused(CARD_NOT_FOUND);
            }
            return Reply.ok(render(card));
        });
    }

    /** 在卡上新增一个套餐（加包或新订阅），即刻起算有效期；返回套餐引用。 */
    Reply<String> addPlan(String requestNo, String ref, String iccid, MockPackage pkg) {
        return call(Operation.ADD_PLAN, requestNo, iccid, pkg.code(), () -> {
            Supplier<String> add = () -> {
                Instant at = now();
                String planRef = newRef("P", at, pkg.code());
                mutate(ref, card -> {
                    if (card.lifecycle() == Lifecycle.TERMINATED) {
                        throw new Refused(CARD_TERMINATED);
                    }
                    List<Plan> plans = new ArrayList<>(card.plans());
                    plans.add(plan(planRef, pkg, at));
                    return card.withPlans(card.activated(), plans);
                });
                return planRef;
            };
            if (idempotencyKey) {
                return Reply.ok(journal.computeIfAbsent(requestNo, k -> add.get()));
            }
            String planRef = add.get();
            journal.put(requestNo, planRef);
            return Reply.ok(planRef);
        });
    }

    Reply<WireCard> changeStatus(String requestNo, String ref, String iccid, Operation op) {
        return call(op, requestNo, iccid, null, () -> {
            Card changed = mutate(ref, card -> {
                if (card.lifecycle() == Lifecycle.TERMINATED && op != Operation.TERMINATE) {
                    throw new Refused(CARD_TERMINATED);
                }
                return card.with(switch (op) {
                    case SUSPEND -> Lifecycle.SUSPENDED;
                    case RESUME -> Lifecycle.LIVE;
                    case TERMINATE -> Lifecycle.TERMINATED;
                    default -> throw new IllegalArgumentException(op.name());
                });
            });
            journal.put(requestNo, ref);
            return Reply.ok(render(changed));
        });
    }

    Reply<String> credential(String ref, String iccid) {
        return call(Operation.FETCH_CREDENTIAL, null, iccid, null, () -> {
            if (card(ref) == null) {
                throw new Refused(CARD_NOT_FOUND);
            }
            return Reply.ok(lpa(ref));
        });
    }

    /** 查档：按 requestNo 查该写操作生效后的引用，未生效回空串。 */
    Reply<String> lookupRequest(String requestNo) {
        return call(Operation.LOOKUP, requestNo, null, null, () -> Reply.ok(journal.getOrDefault(requestNo, "")));
    }

    /** 查档：按 ICCID 查卡上是否已有订阅（不认 requestNo 的上游只能这样查开卡）。 */
    Reply<String> lookupIccid(String requestNo, String iccid) {
        return call(Operation.LOOKUP, requestNo, iccid, null, () -> Reply.ok(iccid == null ? "" : refByIccid.getOrDefault(iccid, "")));
    }

    /** 模拟交码内容；由引用确定，重启后不变。域名用保留的 .invalid，不指向任何真实 SM-DP+。 */
    static String lpa(String ref) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ref.getBytes(StandardCharsets.UTF_8));
            return "LPA:1$smdp.mock.invalid$" + HexFormat.of().withUpperCase().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private WireCard render(Card card) {
        Instant now = clock.instant();
        String status = wire.statusCode(card.status(now));
        List<WirePlan> plans = card.plans().stream().map(p -> new WirePlan(p.ref(), wire.toUnits(p.pkg().quotaBytes()), wire.toUnits(p.usedBytes()),
                wire.metersVoiceAndSms() ? p.pkg().voiceSeconds() : null, wire.metersVoiceAndSms() ? p.pkg().sms() : null,
                wire.formatTime(p.startsAt()), wire.formatTime(p.expiresAt()))).toList();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ref", card.ref());
        json.put("status", status);
        json.put("plans", plans.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", p.ref());
            m.put("quota", p.quota());
            m.put("used", p.used());
            if (wire.metersVoiceAndSms()) {
                m.put("voice_seconds", p.voiceSeconds());
                m.put("sms", p.sms());
            }
            m.put("starts_at", p.startsAt());
            m.put("expires_at", p.expiresAt());
            return m;
        }).toList());
        return new WireCard(card.ref(), status, plans, Json.write(json));
    }

    // ---- 控制台：不经故障注入 ----

    private Card consoleMutate(String ref, UnaryOperator<Card> change) {
        try {
            return mutate(ref, change);
        } catch (Refused e) {
            throw new IllegalArgumentException(adapterKey + " 不认识上游引用 " + ref);
        }
    }

    Card markInUse(String ref, long usedBytes) {
        return consoleMutate(ref, card -> {
            List<Plan> plans = new ArrayList<>(card.plans());
            Plan base = plans.getFirst();
            plans.set(0, new Plan(base.ref(), base.pkg(), Math.clamp(usedBytes, 0, base.pkg().quotaBytes()), base.startsAt(), base.expiresAt()));
            return card.withPlans(true, plans);
        });
    }

    Card markExpired(String ref) {
        Instant past = now().minusSeconds(1);
        return consoleMutate(ref, card -> card.withPlans(card.activated(), card.plans().stream()
                .map(p -> p.expiresAt().isAfter(past) ? new Plan(p.ref(), p.pkg(), p.usedBytes(), p.startsAt(), past) : p).toList()));
    }

    int provisions(String requestNo) {
        AtomicInteger n = provisions.get(requestNo);
        return n == null ? 0 : n.get();
    }

    void reset() {
        cards.clear();
        refByIccid.clear();
        journal.clear();
        provisions.clear();
    }
}
