package com.liroam.supplier.spi.contract.stub;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.contract.UpstreamFixture;

/**
 * 内存里的「上游」：按 {@link StubShape} 用不同的单位与时间格式回数，让适配器有东西可归一。
 * 只服务契约套件，不模拟任何真实供应商。
 */
public final class StubUpstream implements UpstreamFixture {

    static final String OK = "0000";
    static final String THROTTLED = "429";
    static final String SERVER_ERROR = "500";
    static final String BAD_PARAM = "E_PARAM";
    static final String SIGNATURE_HEADER = "X-Stub-Signature";

    static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final ZoneOffset LOCAL_OFFSET = ZoneOffset.ofHours(8);

    private static final byte[] WEBHOOK_SECRET = "contract-stub-webhook".getBytes(StandardCharsets.UTF_8);

    /** 连接没建立：请求未发出。 */
    static final class NotSent extends RuntimeException {
    }

    /** 请求已发出、等不到响应：上游可能已经执行。 */
    static final class TimedOutAfterSend extends RuntimeException {
    }

    record Reply<T>(String code, T body) {
    }

    record Plan(String ref, long quotaUnits, long usedUnits, String expiresRaw) {
    }

    static final class Card {
        final String iccid;
        final String ref;
        String status = "ACTIVE";
        final List<Plan> plans = new ArrayList<>();

        Card(String iccid, String ref) {
            this.iccid = iccid;
            this.ref = ref;
        }
    }

    private final StubShape shape;
    private final Map<String, Card> cardsByRef = new HashMap<>();
    private final Set<String> idlePool = new HashSet<>();
    private final Map<String, String> refByRequestNo = new HashMap<>();
    private final Map<String, Integer> activationsByRequestNo = new HashMap<>();
    private Failure pending;
    private long seq;

    public StubUpstream(StubShape shape) {
        this.shape = shape;
    }

    /** 上游计量单位折算成字节的倍数。 */
    long bytesPerUnit() {
        return shape == StubShape.POOL_IMPORT ? 1024L * 1024L : 1L;
    }

    String formatTime(Instant instant) {
        return shape == StubShape.POOL_IMPORT
                ? LOCAL_TIME.format(LocalDateTime.ofInstant(instant, LOCAL_OFFSET))
                : instant.toString();
    }

    Instant parseTime(String raw) {
        return shape == StubShape.POOL_IMPORT
                ? LocalDateTime.parse(raw, LOCAL_TIME).toInstant(LOCAL_OFFSET)
                : Instant.parse(raw);
    }

    private <T> Reply<T> respond(Runnable effect, java.util.function.Supplier<T> body) {
        Failure failure = pending;
        pending = null;
        if (failure != null) {
            switch (failure) {
                case CONNECT_FAILED -> throw new NotSent();
                case THROTTLED -> {
                    return new Reply<>(THROTTLED, null);
                }
                case SERVER_ERROR -> {
                    return new Reply<>(SERVER_ERROR, null);
                }
                case BAD_REQUEST -> {
                    return new Reply<>(BAD_PARAM, null);
                }
                case UNREGISTERED_CODE -> {
                    return new Reply<>("X-" + (++seq), null);
                }
                case TIMEOUT_AFTER_SEND -> {
                    effect.run();
                    throw new TimedOutAfterSend();
                }
            }
        }
        effect.run();
        return new Reply<>(OK, body.get());
    }

    Reply<String> activate(String requestNo, String iccid, boolean idempotencyKeySent) {
        if (iccid != null && !idlePool.contains(iccid) && !(idempotencyKeySent && refByRequestNo.containsKey(requestNo))) {
            return new Reply<>(BAD_PARAM, null);
        }
        return respond(() -> {
            if (idempotencyKeySent && refByRequestNo.containsKey(requestNo)) {
                return;
            }
            String cardIccid = iccid != null ? iccid : "89852" + String.format("%014d", ++seq) + "F";
            idlePool.remove(cardIccid);
            Card card = new Card(cardIccid, "SUB-" + (++seq));
            cardsByRef.put(card.ref, card);
            refByRequestNo.put(requestNo, card.ref);
            activationsByRequestNo.merge(requestNo, 1, Integer::sum);
        }, () -> refByRequestNo.get(requestNo));
    }

    Card card(String ref) {
        return cardsByRef.get(ref);
    }

    Reply<String> activationRef(String requestNo) {
        return respond(() -> {
        }, () -> refByRequestNo.getOrDefault(requestNo, ""));
    }

    Reply<Card> query(String ref) {
        Card card = cardsByRef.get(ref);
        if (card == null) {
            return new Reply<>(BAD_PARAM, null);
        }
        return respond(() -> {
        }, () -> card);
    }

    Reply<String> addPackage(String ref, String offeringCode) {
        Card card = cardsByRef.get(ref);
        if (card == null) {
            return new Reply<>(BAD_PARAM, null);
        }
        String planRef = "PKG-" + (++seq);
        return respond(() -> card.plans.add(new Plan(planRef, 1024, 0, formatTime(Instant.parse("2027-01-31T15:59:59Z")))), () -> planRef);
    }

    Reply<String> setStatus(String ref, String status) {
        Card card = cardsByRef.get(ref);
        if (card == null) {
            return new Reply<>(BAD_PARAM, null);
        }
        return respond(() -> card.status = status, () -> card.status);
    }

    Reply<String> credential(String ref) {
        Card card = cardsByRef.get(ref);
        if (card == null) {
            return new Reply<>(BAD_PARAM, null);
        }
        return respond(() -> {
        }, () -> "LPA:1$smdp.example$" + card.ref);
    }

    static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void reset() {
        cardsByRef.clear();
        idlePool.clear();
        refByRequestNo.clear();
        activationsByRequestNo.clear();
        pending = null;
    }

    @Override
    public String idleIccid() {
        String iccid = "89852" + String.format("%014d", ++seq) + "F";
        idlePool.add(iccid);
        return iccid;
    }

    @Override
    public void failNext(Failure failure) {
        pending = failure;
    }

    @Override
    public int effectiveProvisions(String requestNo) {
        return activationsByRequestNo.getOrDefault(requestNo, 0);
    }

    @Override
    public ExpectedUsage seedUsage(CardRef card) {
        Instant expiresAt = Instant.parse("2026-12-31T15:59:59Z");
        String raw = formatTime(expiresAt);
        cardsByRef.get(card.upstreamRef()).plans.add(new Plan("PKG-" + (++seq), 2048, 512, raw));
        return new ExpectedUsage(1536 * bytesPerUnit(), expiresAt, raw);
    }

    @Override
    public String baseOfferingCode() {
        return "BASE-1";
    }

    @Override
    public String topUpOfferingCode() {
        return "TOPUP-1";
    }

    @Override
    public SignedCallback validCallback(CardRef card) {
        seedUsage(card);
        Plan plan = cardsByRef.get(card.upstreamRef()).plans.getLast();
        byte[] body = String.join("|", card.upstreamRef(), plan.ref(), Long.toString(plan.quotaUnits()), Long.toString(plan.usedUnits()), plan.expiresRaw())
                .getBytes(StandardCharsets.UTF_8);
        return new SignedCallback(Map.of(SIGNATURE_HEADER, sign(body)), body);
    }

    @Override
    public SignedCallback tamperedCallback(CardRef card) {
        SignedCallback valid = validCallback(card);
        byte[] body = valid.body().clone();
        body[body.length - 1] ^= 1;
        return new SignedCallback(valid.headers(), body);
    }
}
