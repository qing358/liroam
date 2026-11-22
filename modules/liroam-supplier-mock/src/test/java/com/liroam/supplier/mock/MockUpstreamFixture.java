package com.liroam.supplier.mock;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.contract.UpstreamFixture;

/**
 * 契约套件的上游钩子：经 {@link MockUpstreamConsole} 与 {@link MockFaults} 操纵模拟上游。
 * 期望值按套餐码与固定时钟手算，不复用实现里的换算。
 */
final class MockUpstreamFixture implements UpstreamFixture {

    static final Instant NOW = Instant.parse("2026-11-22T04:00:00Z");
    static final long MIB = 1024L * 1024L;
    static final long GIB = 1024L * MIB;

    /** 19 位、末位 0：不会命中默认故障规则的 9991/9992 尾号。 */
    private static final AtomicLong ICCIDS = new AtomicLong();

    static String freshIccid() {
        return String.format("8988800%011d0", ICCIDS.incrementAndGet());
    }

    private final String adapterKey;
    private final MockFaults faults;
    private final MockUpstreamConsole console;
    /** 基础套餐 5GB-7D 在 {@link #NOW} 开通后的到期时刻，按该形状上游的原样格式书写。 */
    private final String baseExpiresAtRaw;
    private final String webhookSecret;

    private MockUpstreamFixture(String adapterKey, MockFaults faults, MockUpstreamConsole console, String baseExpiresAtRaw, String webhookSecret) {
        this.adapterKey = adapterKey;
        this.faults = faults;
        this.console = console;
        this.baseExpiresAtRaw = baseExpiresAtRaw;
        this.webhookSecret = webhookSecret;
    }

    static MockUpstreamFixture shapeA(MockShapeAAdapter adapter, MockFaults faults) {
        return new MockUpstreamFixture(adapter.adapterKey(), faults, new MockUpstreamConsole(List.of(adapter)), "2026-11-29 12:00:00", null);
    }

    static MockUpstreamFixture shapeB(MockShapeBAdapter adapter, MockFaults faults, String webhookSecret) {
        return new MockUpstreamFixture(adapter.adapterKey(), faults, new MockUpstreamConsole(List.of(adapter)), "2026-11-29T04:00:00Z", webhookSecret);
    }

    MockUpstreamConsole console() {
        return console;
    }

    @Override
    public void reset() {
        console.reset(adapterKey);
        faults.clear(adapterKey);
    }

    @Override
    public String idleIccid() {
        return freshIccid();
    }

    @Override
    public void failNext(Failure failure) {
        faults.failNext(adapterKey, MockFaults.Operation.PROVISION, MockFaults.Failure.valueOf(failure.name()));
    }

    @Override
    public int effectiveProvisions(String requestNo) {
        return console.effectiveProvisions(adapterKey, requestNo);
    }

    @Override
    public ExpectedUsage seedUsage(CardRef card) {
        console.activate(adapterKey, card.upstreamRef(), 512 * MIB);
        return new ExpectedUsage(5 * GIB - 512 * MIB, Instant.parse("2026-11-29T04:00:00Z"), baseExpiresAtRaw);
    }

    @Override
    public String baseOfferingCode() {
        return "5GB-7D";
    }

    @Override
    public String topUpOfferingCode() {
        return "1GB-1D";
    }

    /** 形状 B 回调体：application/x-www-form-urlencoded，签名头 X-Mock-Signature = hex(HMAC-SHA256(密钥, 原始体))。 */
    static byte[] callbackBody(String cardRef, String planRef, String status, long quotaBytes, long usedBytes, String startsAt, String expiresAt) {
        String body = "card_ref=" + enc(cardRef) + "&plan_ref=" + enc(planRef) + "&status=" + status
                + "&quota_bytes=" + quotaBytes + "&used_bytes=" + usedBytes + "&voice_seconds=0&sms=0"
                + "&starts_at=" + enc(startsAt) + "&expires_at=" + enc(expiresAt);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public SignedCallback validCallback(CardRef card) {
        byte[] body = callbackBody(card.upstreamRef(), card.upstreamRef(), "ACTIVE", 5 * GIB, GIB, "2026-11-22T04:00:00Z", "2026-11-29T04:00:00Z");
        return new SignedCallback(Map.of("X-Mock-Signature", sign(webhookSecret, body)), body);
    }

    @Override
    public SignedCallback tamperedCallback(CardRef card) {
        SignedCallback valid = validCallback(card);
        byte[] body = valid.body().clone();
        body[body.length - 1] ^= 1;
        return new SignedCallback(valid.headers(), body);
    }
}
