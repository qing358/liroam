package com.liroam.supplier.adapter.quadcell;

import static com.liroam.supplier.adapter.quadcell.QuadcellStubUpstream.API_KEY;
import static com.liroam.supplier.adapter.quadcell.QuadcellStubUpstream.BASE_PACK;
import static com.liroam.supplier.adapter.quadcell.QuadcellStubUpstream.PREFIX;
import static com.liroam.supplier.adapter.quadcell.QuadcellStubUpstream.TOPUP_PACK;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.liroam.supplier.adapter.quadcell.QuadcellStubUpstream.Recorded;
import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.LookupQuery;
import com.liroam.supplier.spi.LookupQuery.WriteOperation;
import com.liroam.supplier.spi.LookupResult.Effect;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.StatusCommand;
import com.liroam.supplier.spi.StatusResult;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;
import com.liroam.supplier.spi.contract.UpstreamFixture.Failure;

/** 适配器分叉表：上游码分级、HTTP 状态、超时、载荷映射、单位与时区、脱敏、查档口径。 */
class QuadcellAdapterTest {

    private static final QuadcellStubUpstream UPSTREAM = QuadcellStubUpstream.start();
    private static final AtomicLong IDS = new AtomicLong();

    private QuadcellAdapter adapter;

    @AfterAll
    static void stop() {
        UPSTREAM.close();
    }

    @BeforeEach
    void reset() {
        UPSTREAM.reset();
        UPSTREAM.properties().setDefaultMode(1);
        UPSTREAM.properties().setBytesPerMb(1024L * 1024L);
        UPSTREAM.properties().setThrottleRetryAfter(Duration.ofSeconds(30));
        UPSTREAM.properties().setReadTimeout(QuadcellStubUpstream.READ_TIMEOUT);
        adapter = new QuadcellAdapter(UPSTREAM.properties());
    }

    private static String requestNo() {
        return "PROVISION-" + IDS.incrementAndGet() + "-0-1";
    }

    private Outcome<ProvisionResult> provision(String iccid, Map<String, String> payload) {
        return adapter.provision(new ProvisionCommand(requestNo(), IDS.incrementAndGet(), iccid, BASE_PACK, payload));
    }

    private CardRef provisioned() {
        String iccid = UPSTREAM.idleIccid();
        ProvisionResult r = ok(provision(iccid, null));
        return new CardRef(IDS.incrementAndGet(), iccid, r.upstreamRef());
    }

    @SuppressWarnings("unchecked")
    private static <T> T ok(Outcome<T> outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        return ((Outcome.Ok<T>) outcome).value();
    }

    private static String rejectedCode(Outcome<?> outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
        return ((Outcome.Rejected<?>) outcome).code();
    }

    private static final Map<String, Class<?>> KIND = Map.of(
            "R", Outcome.Rejected.class, "T", Outcome.Retryable.class, "U", Outcome.Unknown.class, "OK", Outcome.Ok.class);

    // ---- 请求形状 ----

    @Test
    void provisionSendsDocumentedRequestAndMapsOfferingPayload() {
        String iccid = UPSTREAM.idleIccid();
        Map<String, String> payload = Map.of("mode", "2", "vDay", "30", "svcCode", "PROF01", "actwPack", "true",
                "pcrfCode", "FUP01", "pcrfReset", "1", "autoAct", "true", "priority", "H");

        ProvisionResult result = ok(provision(iccid, payload));

        Recorded req = UPSTREAM.lastRequest();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo(PREFIX + "/sim-profile/" + iccid);
        assertThat(req.headers()).containsEntry("x-api-key", API_KEY).containsEntry("time-zone", "Asia/Hong_Kong")
                .containsEntry("content-type", "application/json");
        var body = req.json();
        assertThat(body.path("mode").asInt()).isEqualTo(2);
        assertThat(body.path("vDay").isInt()).isTrue();
        assertThat(body.path("vDay").asInt()).isEqualTo(30);
        assertThat(body.path("svcCode").asString()).isEqualTo("PROF01");
        assertThat(body.path("actwPack").isBoolean()).isTrue();
        var pack = body.path("pack");
        assertThat(pack.path("packCode").asString()).isEqualTo(BASE_PACK);
        assertThat(pack.path("pcrfCode").asString()).isEqualTo("FUP01");
        assertThat(pack.path("pcrfReset").asInt()).isEqualTo(1);
        assertThat(pack.path("autoAct").asBoolean()).isTrue();
        assertThat(pack.path("priority").asString()).isEqualTo("H");
        assertThat(result.iccid()).isEqualTo(iccid);
        assertThat(result.upstreamRef()).isNotBlank();
        assertThat(result.credential()).isEmpty();
    }

    @Test
    void provisionUsesDefaultModeOnlyWhenPayloadHasNone() {
        String iccid = UPSTREAM.idleIccid();
        ok(provision(iccid, null));
        assertThat(UPSTREAM.lastRequest().json().path("mode").asInt()).isEqualTo(1);
        assertThat(UPSTREAM.lastRequest().json().has("vDay")).as("未配置的可选字段不发").isFalse();
    }

    @Test
    void badLocalInputIsRejectedWithoutCallingUpstream() {
        String iccid = UPSTREAM.idleIccid();
        assertThat(rejectedCode(provision(iccid, Map.of("colour", "red")))).isEqualTo("BAD_PAYLOAD");
        assertThat(rejectedCode(provision(iccid, Map.of("mode", "x")))).isEqualTo("BAD_PAYLOAD");
        assertThat(rejectedCode(provision(iccid, Map.of("actwPack", "yes")))).isEqualTo("BAD_PAYLOAD");
        assertThat(rejectedCode(provision(null, null))).isEqualTo("BAD_REQUEST");
        assertThat(rejectedCode(adapter.provision(new ProvisionCommand(requestNo(), 1, iccid, " ", null)))).isEqualTo("BAD_PACKAGE");
        UPSTREAM.properties().setDefaultMode(null);
        assertThat(rejectedCode(provision(iccid, null))).as("mode 两处都没配").isEqualTo("BAD_PAYLOAD");
        assertThat(UPSTREAM.requests()).isEmpty();
    }

    @Test
    void addPlanPostsPackageWithPackFieldsOnly() {
        CardRef card = provisioned();

        String ref = ok(adapter.addPlan(new TopUpCommand("ADD_PLAN-1-2-1", card, TOPUP_PACK, Map.of("priority", "L", "autoAct", "false")))).upstreamPlanRef();

        Recorded req = UPSTREAM.lastRequest();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo(PREFIX + "/sim-profile/" + card.iccid() + "/package");
        assertThat(req.json().path("packCode").asString()).isEqualTo(TOPUP_PACK);
        assertThat(req.json().path("priority").asString()).isEqualTo("L");
        assertThat(req.json().path("autoAct").asBoolean(true)).isFalse();
        assertThat(ref).isNotBlank();
        int calls = UPSTREAM.requests().size();
        assertThat(rejectedCode(adapter.addPlan(new TopUpCommand("ADD_PLAN-1-3-1", card, TOPUP_PACK, Map.of("mode", "1")))))
                .as("开订阅字段不能用于加包").isEqualTo("BAD_PAYLOAD");
        assertThat(UPSTREAM.requests()).hasSize(calls);
    }

    @Test
    void fetchCredentialIsUnsupportedAndNeverCallsUpstream() {
        CardRef card = provisioned();
        int calls = UPSTREAM.requests().size();
        assertThat(rejectedCode(adapter.fetchCredential(card))).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(UPSTREAM.requests()).hasSize(calls);
    }

    // ---- 错误分级（§7 内部错误码） ----

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "1000,R", "1001,R", "1004,R", "1008,R", "1060,R",
            "2001,T",
            "2002,R", "2003,R", "2004,R", "2005,R", "2006,R", "2007,R", "2008,R", "2009,R", "2010,R", "2031,R", "2032,R",
            "2041,U", "2042,U", "3001,U", "3002,U", "3003,U", "3004,U", "9000,U",
            "7777,U"})
    void upstreamCodesAreClassified(int code, String kind) {
        UPSTREAM.script(400, Map.of("requestId", "req-abc"), QuadcellStubUpstream.error(code, "iccid", "msg-" + code, PREFIX + "/sim-profile/8985200999999999999"));

        Outcome<ProvisionResult> outcome = provision(UPSTREAM.idleIccid(), null);

        assertThat(outcome).isInstanceOf(KIND.get(kind));
        assertThat(outcome.toString()).contains(String.valueOf(code)).contains("req-abc")
                .doesNotContain(API_KEY).doesNotContain("8985200999999999999");
        if (outcome instanceof Outcome.Rejected<?> r) {
            assertThat(r.code()).isEqualTo(String.valueOf(code));
            assertThat(r.message()).contains("msg-" + code);
        }
    }

    @ParameterizedTest(name = "写 HTTP {0} → {1}")
    @CsvSource({"401,R", "403,R", "404,R", "400,R", "429,T", "503,T", "500,U", "502,U", "504,U"})
    void httpStatusWithoutErrorBodyOnWrite(int status, String kind) {
        UPSTREAM.script(status, Map.of(), "");
        Outcome<ProvisionResult> outcome = provision(UPSTREAM.idleIccid(), null);
        assertThat(outcome).isInstanceOf(KIND.get(kind));
        if (status == 401 || status == 403) {
            assertThat(rejectedCode(outcome)).isEqualTo("AUTH_FAILED");
        }
    }

    @ParameterizedTest(name = "读 HTTP {0} → {1}")
    @CsvSource({"500,T", "502,T", "503,T", "401,R"})
    void httpStatusWithoutErrorBodyOnRead(int status, String kind) {
        CardRef card = provisioned();
        UPSTREAM.script(status, Map.of(), "");
        assertThat(adapter.queryUsage(card)).isInstanceOf(KIND.get(kind));
    }

    @Test
    void throttleHonoursRetryAfterHeaderElseConfiguredDefault() {
        UPSTREAM.script(429, Map.of("Retry-After", "7"), "");
        Outcome<ProvisionResult> withHeader = provision(UPSTREAM.idleIccid(), null);
        assertThat(((Outcome.Retryable<?>) withHeader).retryAfter()).isEqualTo(Duration.ofSeconds(7));

        UPSTREAM.script(429, Map.of(), "");
        Outcome<ProvisionResult> without = provision(UPSTREAM.idleIccid(), null);
        assertThat(((Outcome.Retryable<?>) without).retryAfter()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void timeoutAfterSendIsUnknownForWritesAndRetryableForReads() {
        UPSTREAM.properties().setReadTimeout(QuadcellStubUpstream.TIMEOUT_PROBE_READ_TIMEOUT);
        UPSTREAM.failNext(Failure.TIMEOUT_AFTER_SEND);
        assertThat(provision(UPSTREAM.idleIccid(), null)).isInstanceOf(Outcome.Unknown.class);

        CardRef card = provisioned();
        UPSTREAM.failNext(Failure.TIMEOUT_AFTER_SEND);
        assertThat(adapter.queryUsage(card)).isInstanceOf(Outcome.Retryable.class);
    }

    @Test
    void connectFailureIsRetryableForReadsToo() {
        CardRef card = provisioned();
        UPSTREAM.failNext(Failure.CONNECT_FAILED);
        assertThat(adapter.queryUsage(card)).isInstanceOf(Outcome.Retryable.class);
    }

    @Test
    void okWithoutPackIdOrWithGarbageBodyIsUnknown() {
        UPSTREAM.script(200, Map.of(), "{}");
        assertThat(provision(UPSTREAM.idleIccid(), null)).isInstanceOf(Outcome.Unknown.class);
        UPSTREAM.script(200, Map.of(), "<html>gateway</html>");
        assertThat(provision(UPSTREAM.idleIccid(), null)).isInstanceOf(Outcome.Unknown.class);
    }

    // ---- 查档 8.5：单位、时间、状态、脱敏 ----

    @Test
    void documentSampleDetailsAreNormalizedAndMasked() throws IOException {
        String sample = resource("quadcell/sim-profile-details-doc-sample.json");
        UPSTREAM.script(200, Map.of(), sample);

        UsageSnapshot snapshot = ok(adapter.queryUsage(new CardRef(1, "1000000000000000014", "69ba6aec1c81c5102bfba46f")));

        assertThat(snapshot.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(snapshot.cardUpstreamRef()).isEqualTo("69ba6aec1c81c5102bfba46f");
        assertThat(snapshot.plans()).singleElement().satisfies(plan -> {
            assertThat(plan.upstreamPlanRef()).isEqualTo("69ba6aec1c81c5102bfba46f");
            assertThat(plan.quotaBytes()).as("无限量").isNull();
            assertThat(plan.remainingBytes()).isNull();
            assertThat(plan.startsAt()).isEqualTo(Instant.parse("2026-03-18T09:56:14Z"));
            assertThat(plan.startsAtRaw()).isEqualTo(" 2026-03-18T17:56:14+08:00");
            assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-03-25T09:56:14Z"));
            assertThat(plan.expiresAtRaw()).isEqualTo("2026-03-25T17:56:14+08:00");
            assertThat(plan.voiceRemainingSeconds()).isNull();
            assertThat(plan.smsRemaining()).isNull();
        });
        assertThat(snapshot.rawJson()).contains("\"svcCode\"").contains("0014")
                .doesNotContain("1000000000000000014").doesNotContain("100000000000014").doesNotContain("JKFLWKEJ9234k91HSKFDE");
    }

    @Test
    void quotaInMegabytesBecomesBytesWithConfiguredBase() {
        CardRef card = provisioned();
        UPSTREAM.seedUsage(card);

        PlanUsage plan = ok(adapter.queryUsage(card)).plans().getFirst();
        assertThat(plan.quotaBytes()).isEqualTo(3072L * 1024 * 1024);
        assertThat(plan.remainingBytes()).isEqualTo(Math.round(1024.5 * 1024 * 1024));
        assertThat(plan.usedBytes()).isEqualTo(Math.round(2047.5 * 1024 * 1024));

        UPSTREAM.properties().setBytesPerMb(1_000_000L);
        assertThat(ok(adapter.queryUsage(card)).plans().getFirst().remainingBytes()).isEqualTo(1_024_500_000L);
    }

    @Test
    void timeWithoutOffsetIsReadInConfiguredZone() {
        UPSTREAM.script(200, Map.of(), """
                {"iccid":"8985200000000000001","status":3,"packs":[{"packId":"p1","packCode":"X","iQuota":10,"rQuota":10,
                 "actDate":null,"expDate":"2026-04-30T00:00:00","status":1}]}""");
        PlanUsage plan = ok(adapter.queryUsage(new CardRef(1, "8985200000000000001", "p1"))).plans().getFirst();
        assertThat(plan.expiresAt()).isEqualTo(Instant.parse("2026-04-29T16:00:00Z"));
        assertThat(plan.startsAt()).isNull();
    }

    @ParameterizedTest(name = "status {0} → {1}")
    @CsvSource({"2,INACTIVE", "3,INACTIVE", "4,ACTIVE", "5,SUSPENDED", "6,EXPIRED", "9,"})
    void cardStatusCodes(int code, CardStatus expected) {
        UPSTREAM.script(200, Map.of(), "{\"iccid\":\"8985200000000000001\",\"status\":" + code + ",\"packs\":[]}");
        Outcome<UsageSnapshot> outcome = adapter.queryUsage(new CardRef(1, "8985200000000000001", null));
        if (expected == null) {
            assertThat(outcome).as("未登记状态码").isInstanceOf(Outcome.Unknown.class);
        } else {
            assertThat(ok(outcome).status()).isEqualTo(expected);
        }
    }

    // ---- 8.8 / 8.9 / 8.10 ----

    @Test
    void statusOperationsFollowDocumentedPathsAndPreconditions() {
        CardRef card = provisioned();
        assertThat(rejectedCode(adapter.changeStatus(new StatusCommand("SUSPEND-1-0-1", card, StatusOp.SUSPEND))))
                .as("未激活不能停用").isEqualTo("2004");

        UPSTREAM.activate(card);
        assertThat(ok(adapter.changeStatus(new StatusCommand("SUSPEND-1-0-2", card, StatusOp.SUSPEND))).status()).isEqualTo(CardStatus.SUSPENDED);
        assertThat(UPSTREAM.lastRequest().method() + " " + UPSTREAM.lastRequest().path()).isEqualTo("POST " + PREFIX + "/sim-profile/" + card.iccid() + "/suspend");

        StatusResult resumed = ok(adapter.changeStatus(new StatusCommand("RESUME-1-0-1", card, StatusOp.RESUME)));
        assertThat(resumed.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(UPSTREAM.lastRequest().path()).endsWith("/resume");

        assertThat(ok(adapter.changeStatus(new StatusCommand("TERMINATE-1-0-1", card, StatusOp.TERMINATE))).status()).isEqualTo(CardStatus.TERMINATED);
        assertThat(UPSTREAM.lastRequest().method() + " " + UPSTREAM.lastRequest().path()).isEqualTo("DELETE " + PREFIX + "/sim-profile/" + card.iccid());
    }

    // ---- 查档判定 ----

    private Effect effect(WriteOperation op, CardRef card) {
        return ok(adapter.lookup(new LookupQuery("X-" + IDS.incrementAndGet() + "-0-1", op, card))).effect();
    }

    @Test
    void lookupDecidesByProfileState() {
        String iccid = UPSTREAM.idleIccid();
        CardRef idle = new CardRef(1, iccid, null);
        assertThat(effect(WriteOperation.PROVISION, idle)).isEqualTo(Effect.NOT_EFFECTIVE);

        String packId = ok(provision(iccid, null)).upstreamRef();
        assertThat(ok(adapter.lookup(new LookupQuery("X-1-0-1", WriteOperation.PROVISION, idle))).upstreamRef()).isEqualTo(packId);

        CardRef card = new CardRef(1, iccid, packId);
        UPSTREAM.activate(card);
        assertThat(effect(WriteOperation.SUSPEND, card)).isEqualTo(Effect.NOT_EFFECTIVE);
        UPSTREAM.setStatus(iccid, 5);
        assertThat(effect(WriteOperation.SUSPEND, card)).isEqualTo(Effect.EFFECTIVE);
        assertThat(effect(WriteOperation.RESUME, card)).isEqualTo(Effect.NOT_EFFECTIVE);
        UPSTREAM.setStatus(iccid, 4);
        assertThat(effect(WriteOperation.RESUME, card)).isEqualTo(Effect.EFFECTIVE);
        assertThat(effect(WriteOperation.TERMINATE, card)).isEqualTo(Effect.NOT_EFFECTIVE);

        ok(adapter.changeStatus(new StatusCommand("TERMINATE-9-0-1", card, StatusOp.TERMINATE)));
        assertThat(effect(WriteOperation.TERMINATE, card)).as("终止后回到 Idle").isEqualTo(Effect.EFFECTIVE);
        UPSTREAM.script(404, Map.of(), QuadcellStubUpstream.error(1060, "iccid", "No record found", PREFIX + "/sim-profile/x"));
        assertThat(effect(WriteOperation.TERMINATE, card)).as("终止后查无此卡").isEqualTo(Effect.EFFECTIVE);
        UPSTREAM.script(404, Map.of(), QuadcellStubUpstream.error(1060, "iccid", "No record found", PREFIX + "/sim-profile/x"));
        assertThat(rejectedCode(adapter.lookup(new LookupQuery("X-2-0-1", WriteOperation.PROVISION, card)))).isEqualTo("1060");
    }

    @Test
    void addPlanCannotBeLookedUpAndGoesToManualWithoutCalling() {
        CardRef card = provisioned();
        int calls = UPSTREAM.requests().size();
        assertThat(rejectedCode(adapter.lookup(new LookupQuery("ADD_PLAN-1-2-1", WriteOperation.ADD_PLAN, card)))).isEqualTo("LOOKUP_UNSUPPORTED");
        assertThat(UPSTREAM.requests()).hasSize(calls);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = QuadcellAdapterTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
