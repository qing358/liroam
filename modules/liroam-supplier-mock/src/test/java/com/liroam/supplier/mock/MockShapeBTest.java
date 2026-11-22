package com.liroam.supplier.mock;

import static com.liroam.supplier.mock.MockUpstreamFixture.GIB;
import static com.liroam.supplier.mock.MockUpstreamFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;
import com.liroam.supplier.spi.WebhookParser;

/** 形状 B：下单即建并同步带回 LPA、字节与 ISO-8601、语音短信、幂等键、HMAC 回调、默认 Rejected 规则。 */
class MockShapeBTest {

    private static final String SECRET = "b-test-secret";

    private final MutableClock clock = new MutableClock(NOW);
    private final MockFaults faults = new MockFaults(MockSupplierProperties.defaultRules());
    private final MockShapeBAdapter adapter = new MockShapeBAdapter(faults, clock, SECRET);
    private final MockUpstreamConsole console = new MockUpstreamConsole(List.of(adapter));
    private long seq;

    private ProvisionCommand command(String offering) {
        seq++;
        return new ProvisionCommand("PROVISION-" + seq + "-0-1", seq, null, offering, null);
    }

    private static <T> T ok(Outcome<T> outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        return ((Outcome.Ok<T>) outcome).value();
    }

    @Test
    void provisionCreatesCardAndReturnsLpaThatMatchesApiFetch() {
        ProvisionCommand c = command("5GB-7D");

        ProvisionResult r = ok(adapter.provision(c));
        ProvisionResult other = ok(adapter.provision(command("5GB-7D")));

        assertThat(r.iccid()).matches("\\d{19}").isNotEqualTo(other.iccid());
        CredentialResult credential = r.credential().orElseThrow();
        assertThat(credential.kind()).isEqualTo(CredentialResult.Kind.LPA);
        assertThat(credential.payload()).startsWith("LPA:1$");
        CardRef card = new CardRef(c.cardAssetId(), r.iccid(), r.upstreamRef());
        assertThat(ok(adapter.fetchCredential(card)).payload()).isEqualTo(credential.payload());
    }

    @Test
    void concurrentReplaysOfOneRequestNoProvisionOnce() throws Exception {
        ProvisionCommand c = command("5GB-7D");
        Set<String> refs = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 32; i++) {
            pool.submit(() -> {
                start.await();
                refs.add(ok(adapter.provision(c)).upstreamRef());
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(refs).hasSize(1);
        assertThat(console.effectiveProvisions(MockShapeBAdapter.ADAPTER_KEY, c.requestNo())).isEqualTo(1);
    }

    @Test
    void usageIsInBytesWithIsoTimesAndVoiceSmsFromPackage() {
        ProvisionCommand c = command("3GB-30D-100MIN-50SMS");
        ProvisionResult r = ok(adapter.provision(c));

        UsageSnapshot s = ok(adapter.queryUsage(new CardRef(c.cardAssetId(), r.iccid(), r.upstreamRef())));

        assertThat(s.status()).isEqualTo(CardStatus.INACTIVE);
        assertThat(s.rawJson()).startsWith("{").contains(Long.toString(3 * GIB));
        PlanUsage p = s.plans().getFirst();
        assertThat(p.quotaBytes()).isEqualTo(3 * GIB);
        assertThat(p.remainingBytes()).isEqualTo(3 * GIB);
        assertThat(p.voiceRemainingSeconds()).isEqualTo(6000L);
        assertThat(p.smsRemaining()).isEqualTo(50);
        assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-12-22T04:00:00Z"));
        assertThat(p.expiresAtRaw()).isEqualTo("2026-12-22T04:00:00Z");
        assertThat(p).extracting(PlanUsage::voiceQuotaSeconds, PlanUsage::voiceUsedSeconds, PlanUsage::smsQuota, PlanUsage::smsUsed)
                .containsExactly(6000L, 0L, 50, 0);
    }

    @Test
    void consoleCallsAreReportedAsUsedAndRemainingVoiceSms() {
        ProvisionCommand c = command("3GB-30D-100MIN-50SMS");
        ProvisionResult r = ok(adapter.provision(c));

        console.useCalls(MockShapeBAdapter.ADAPTER_KEY, r.upstreamRef(), 900L, 60);
        console.useCalls(MockShapeBAdapter.ADAPTER_KEY, r.upstreamRef(), null, null);
        UsageSnapshot s = ok(adapter.queryUsage(new CardRef(c.cardAssetId(), r.iccid(), r.upstreamRef())));

        assertThat(s.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(s.plans().getFirst()).extracting(PlanUsage::voiceUsedSeconds, PlanUsage::voiceRemainingSeconds, PlanUsage::smsUsed,
                PlanUsage::smsRemaining).containsExactly(900L, 5100L, 50, 0);
    }

    @Test
    void reservedPackageCodeIsAlwaysRejectedAndNeverProvisions() {
        ProvisionCommand c = command("9GB-9D");

        assertThat(adapter.provision(c)).isInstanceOf(Outcome.Rejected.class);
        assertThat(adapter.provision(c)).isInstanceOf(Outcome.Rejected.class);
        assertThat(console.effectiveProvisions(MockShapeBAdapter.ADAPTER_KEY, c.requestNo())).isZero();
    }

    @Test
    void newSubscriptionTopUpIsIdempotentOnRequestNo() {
        ProvisionCommand c = command("5GB-7D");
        ProvisionResult r = ok(adapter.provision(c));
        CardRef card = new CardRef(c.cardAssetId(), r.iccid(), r.upstreamRef());
        TopUpCommand topUp = new TopUpCommand("ADD_PLAN-1-0-1", card, "1GB-1D", null);

        String first = ok(adapter.addPlan(topUp)).upstreamPlanRef();
        String replay = ok(adapter.addPlan(topUp)).upstreamPlanRef();

        assertThat(replay).isEqualTo(first);
        assertThat(ok(adapter.queryUsage(card)).plans()).hasSize(2);
    }

    @Test
    void webhookRejectsMissingOrForeignSignatureAndNormalizesValidOne() {
        WebhookParser parser = adapter.webhook().orElseThrow();
        byte[] body = MockUpstreamFixture.callbackBody("MB-ref", "MB-plan", "ACTIVE", 5 * GIB, GIB, "2026-11-22T04:00:00Z", "2026-11-29T04:00:00Z");

        assertThat(parser.parse(Map.of(), body)).isInstanceOf(Outcome.Rejected.class);
        assertThat(parser.parse(Map.of("X-Mock-Signature", MockUpstreamFixture.sign("other", body)), body)).isInstanceOf(Outcome.Rejected.class);
        assertThat(parser.parse(Map.of("X-Mock-Signature", "zz"), "garbage".getBytes(StandardCharsets.UTF_8))).isInstanceOf(Outcome.Rejected.class);

        UsageSnapshot s = ok(parser.parse(Map.of("x-mock-signature", MockUpstreamFixture.sign(SECRET, body)), body));
        assertThat(s.cardUpstreamRef()).isEqualTo("MB-ref");
        assertThat(s.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(s.rawJson()).startsWith("{").contains("\"card_ref\":\"MB-ref\"");
        assertThat(s.plans()).singleElement().satisfies(p -> {
            assertThat(p.upstreamPlanRef()).isEqualTo("MB-plan");
            assertThat(p.remainingBytes()).isEqualTo(4 * GIB);
            assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-11-29T04:00:00Z"));
        });
    }

    @Test
    void signedButMalformedCallbackIsRejected() {
        byte[] body = "card_ref=x&quota_bytes=abc".getBytes(StandardCharsets.UTF_8);

        assertThat(adapter.webhook().orElseThrow().parse(Map.of("X-Mock-Signature", MockUpstreamFixture.sign(SECRET, body)), body))
                .isInstanceOf(Outcome.Rejected.class);
    }

    @Test
    void afterRestartUsageAndCredentialAreDerivedFromUpstreamRef() {
        ProvisionCommand c = command("5GB-7D");
        ProvisionResult r = ok(adapter.provision(c));
        CardRef card = new CardRef(c.cardAssetId(), r.iccid(), r.upstreamRef());

        MockShapeBAdapter restarted = new MockShapeBAdapter(new MockFaults(List.of()), clock, SECRET);

        UsageSnapshot s = ok(restarted.queryUsage(card));
        assertThat(s.status()).isEqualTo(CardStatus.INACTIVE);
        assertThat(s.plans()).singleElement().satisfies(p -> {
            assertThat(p.remainingBytes()).isEqualTo(5 * GIB);
            assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-11-29T04:00:00Z"));
        });
        assertThat(ok(restarted.fetchCredential(card)).payload()).isEqualTo(r.credential().orElseThrow().payload());
    }
}
