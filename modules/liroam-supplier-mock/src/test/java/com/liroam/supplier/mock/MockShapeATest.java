package com.liroam.supplier.mock;

import static com.liroam.supplier.mock.MockUpstreamFixture.GIB;
import static com.liroam.supplier.mock.MockUpstreamFixture.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liroam.supplier.mock.MockFaults.Failure;
import com.liroam.supplier.mock.MockFaults.Operation;
import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.LookupQuery;
import com.liroam.supplier.spi.LookupQuery.WriteOperation;
import com.liroam.supplier.spi.LookupResult.Effect;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.StatusCommand;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;

/** 形状 A：池模式、MB 与东八区本地时间、无幂等键（查档按 ICCID）、默认故障规则。 */
class MockShapeATest {

    private final MutableClock clock = new MutableClock(NOW);
    private final MockFaults faults = new MockFaults(MockSupplierProperties.defaultRules());
    private final MockShapeAAdapter adapter = new MockShapeAAdapter(faults, clock);
    private final MockUpstreamConsole console = new MockUpstreamConsole(List.of(adapter));
    private long seq;

    private ProvisionCommand command(String iccid, String offering) {
        seq++;
        return new ProvisionCommand("PROVISION-" + seq + "-0-1", seq, iccid, offering, null);
    }

    private static <T> T ok(Outcome<T> outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        return ((Outcome.Ok<T>) outcome).value();
    }

    private CardRef provision(String iccid, String offering) {
        ProvisionCommand c = command(iccid, offering);
        ProvisionResult r = ok(adapter.provision(c));
        return new CardRef(c.cardAssetId(), r.iccid(), r.upstreamRef());
    }

    private static String rejectedCode(Outcome<?> outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
        return ((Outcome.Rejected<?>) outcome).code();
    }

    @Test
    void freshCardIsReadyWithFullQuotaNormalizedFromMegabytesAndLocalTime() {
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");

        UsageSnapshot s = ok(adapter.queryUsage(card));

        assertThat(s.status()).isEqualTo(CardStatus.INACTIVE);
        assertThat(s.cardUpstreamRef()).isEqualTo(card.upstreamRef());
        assertThat(s.rawJson()).startsWith("{").contains("5120");
        PlanUsage p = s.plans().getFirst();
        assertThat(s.plans()).hasSize(1);
        assertThat(p.quotaBytes()).isEqualTo(5 * GIB);
        assertThat(p.usedBytes()).isZero();
        assertThat(p.remainingBytes()).isEqualTo(5 * GIB);
        assertThat(p.voiceRemainingSeconds()).isNull();
        assertThat(p.smsRemaining()).isNull();
        assertThat(p.startsAt()).isEqualTo(NOW);
        assertThat(p.startsAtRaw()).isEqualTo("2026-11-22 12:00:00");
        assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-11-29T04:00:00Z"));
        assertThat(p.expiresAtRaw()).isEqualTo("2026-11-29 12:00:00");
    }

    @Test
    void unparseablePackageCodesAreRejectedAsBadPackage() {
        assertThat(rejectedCode(adapter.provision(command(MockUpstreamFixture.freshIccid(), "5G-7D")))).isEqualTo("BAD_PACKAGE");
        assertThat(rejectedCode(adapter.provision(command(MockUpstreamFixture.freshIccid(), "0GB-7D")))).isEqualTo("BAD_PACKAGE");
        assertThat(rejectedCode(adapter.provision(command(MockUpstreamFixture.freshIccid(), null)))).isEqualTo("BAD_PACKAGE");
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");
        assertThat(rejectedCode(adapter.addPlan(new TopUpCommand("ADD_PLAN-1-0-1", card, "1GB", null)))).isEqualTo("BAD_PACKAGE");
    }

    @Test
    void poolModeRequiresIccidAndRefusesAnIccidAlreadyInUse() {
        assertThat(adapter.provision(command(null, "5GB-7D"))).isInstanceOf(Outcome.Rejected.class);
        String iccid = MockUpstreamFixture.freshIccid();
        provision(iccid, "5GB-7D");

        assertThat(rejectedCode(adapter.provision(command(iccid, "1GB-1D")))).isEqualTo("ICCID_IN_USE");
    }

    @Test
    void iccidEndingIn9991IsThrottledOnFirstWriteOnly() {
        String iccid = "898880000000000" + "9991";

        Outcome<ProvisionResult> first = adapter.provision(command(iccid, "5GB-7D"));

        assertThat(first).isInstanceOf(Outcome.Retryable.class);
        assertThat(((Outcome.Retryable<?>) first).retryAfter()).isNotNull();
        assertThat(ok(adapter.provision(command(iccid, "5GB-7D"))).iccid()).isEqualTo(iccid);
    }

    @Test
    void iccidEndingIn9992TimesOutAfterSendButIsEffectiveOnLookup() {
        String iccid = "898880000000000" + "9992";
        ProvisionCommand c = command(iccid, "5GB-7D");

        assertThat(adapter.provision(c)).isInstanceOf(Outcome.Unknown.class);

        var looked = ok(adapter.lookup(new LookupQuery(c.requestNo(), WriteOperation.PROVISION, new CardRef(c.cardAssetId(), iccid, null))));
        assertThat(looked.effect()).isEqualTo(Effect.EFFECTIVE);
        assertThat(looked.upstreamRef()).isNotBlank();
        assertThat(ok(adapter.queryUsage(new CardRef(c.cardAssetId(), iccid, looked.upstreamRef()))).status()).isEqualTo(CardStatus.INACTIVE);
        assertThat(rejectedCode(adapter.provision(command(iccid, "5GB-7D")))).as("不会开第二次").isEqualTo("ICCID_IN_USE");
    }

    @Test
    void rulesCanBeSwitchedOff() {
        MockShapeAAdapter quiet = new MockShapeAAdapter(new MockFaults(List.of()), clock);

        assertThat(quiet.provision(command("898880000000000" + "9991", "5GB-7D"))).isInstanceOf(Outcome.Ok.class);
        assertThat(quiet.provision(command("898880000000000" + "9992", "5GB-7D"))).isInstanceOf(Outcome.Ok.class);
    }

    @Test
    void failNextTargetsOneAdapterAndOneOperation() {
        faults.failNext(MockShapeBAdapter.ADAPTER_KEY, Operation.PROVISION, Failure.SERVER_ERROR);
        faults.failNext(MockShapeAAdapter.ADAPTER_KEY, Operation.QUERY, Failure.SERVER_ERROR);

        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");

        assertThat(adapter.queryUsage(card)).isInstanceOf(Outcome.Retryable.class);
        assertThat(adapter.queryUsage(card)).isInstanceOf(Outcome.Ok.class);
    }

    @Test
    void consoleMarksCardInUseThenExpired() {
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");

        console.activate(MockShapeAAdapter.ADAPTER_KEY, card.upstreamRef(), GIB);
        UsageSnapshot inUse = ok(adapter.queryUsage(card));
        assertThat(inUse.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(inUse.plans().getFirst().remainingBytes()).isEqualTo(4 * GIB);

        console.expire(MockShapeAAdapter.ADAPTER_KEY, card.upstreamRef());
        assertThat(ok(adapter.queryUsage(card)).status()).isEqualTo(CardStatus.EXPIRED);
    }

    @Test
    void readyCardExpiresWhenValidityRunsOutWithoutActivation() {
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "1GB-1D");

        clock.advance(Duration.ofDays(1).minusSeconds(1));
        assertThat(ok(adapter.queryUsage(card)).status()).isEqualTo(CardStatus.INACTIVE);
        clock.advance(Duration.ofSeconds(1));
        assertThat(ok(adapter.queryUsage(card)).status()).isEqualTo(CardStatus.EXPIRED);
    }

    @Test
    void readyCardExpiredFromConsoleWithoutActivation() {
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");

        console.expire(MockShapeAAdapter.ADAPTER_KEY, card.upstreamRef());

        assertThat(ok(adapter.queryUsage(card)).status()).isEqualTo(CardStatus.EXPIRED);
    }

    private CardStatus change(CardRef card, StatusOp op) {
        return ok(adapter.changeStatus(new StatusCommand(op + "-" + (++seq), card, op))).status();
    }

    @Test
    void statusTransitionsKeepActivationAndTerminationIsFinal() {
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");

        assertThat(change(card, StatusOp.SUSPEND)).isEqualTo(CardStatus.SUSPENDED);
        assertThat(change(card, StatusOp.RESUME)).isEqualTo(CardStatus.INACTIVE);
        console.activate(MockShapeAAdapter.ADAPTER_KEY, card.upstreamRef(), 0);
        assertThat(change(card, StatusOp.SUSPEND)).isEqualTo(CardStatus.SUSPENDED);
        assertThat(change(card, StatusOp.RESUME)).isEqualTo(CardStatus.ACTIVE);
        assertThat(change(card, StatusOp.TERMINATE)).isEqualTo(CardStatus.TERMINATED);
        assertThat(adapter.changeStatus(new StatusCommand("SUSPEND-x", card, StatusOp.SUSPEND))).isInstanceOf(Outcome.Rejected.class);
    }

    @Test
    void statusWriteTimingOutIsEffectiveOnLookupByRequestNo() {
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");
        faults.failNext(MockShapeAAdapter.ADAPTER_KEY, Operation.SUSPEND, Failure.TIMEOUT_AFTER_SEND);

        assertThat(adapter.changeStatus(new StatusCommand("SUSPEND-9-0-1", card, StatusOp.SUSPEND))).isInstanceOf(Outcome.Unknown.class);

        assertThat(ok(adapter.lookup(new LookupQuery("SUSPEND-9-0-1", WriteOperation.SUSPEND, card))).effect()).isEqualTo(Effect.EFFECTIVE);
        assertThat(ok(adapter.lookup(new LookupQuery("RESUME-9-0-1", WriteOperation.RESUME, card))).effect()).isEqualTo(Effect.NOT_EFFECTIVE);
        assertThat(ok(adapter.queryUsage(card)).status()).isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    void addPackageAddsAPlanStartingNowOnTheSameCard() {
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");
        clock.advance(Duration.ofHours(1));

        String planRef = ok(adapter.addPlan(new TopUpCommand("ADD_PLAN-1-0-1", card, "1GB-1D", null))).upstreamPlanRef();

        PlanUsage added = ok(adapter.queryUsage(card)).plans().stream().filter(p -> p.upstreamPlanRef().equals(planRef)).findFirst().orElseThrow();
        assertThat(added.quotaBytes()).isEqualTo(GIB);
        assertThat(added.expiresAt()).isEqualTo(Instant.parse("2026-11-23T05:00:00Z"));
        assertThat(added.expiresAtRaw()).isEqualTo("2026-11-23 13:00:00");
    }

    @Test
    void afterRestartUsageIsDerivedFromUpstreamRef() {
        CardRef card = provision(MockUpstreamFixture.freshIccid(), "5GB-7D");
        console.activate(MockShapeAAdapter.ADAPTER_KEY, card.upstreamRef(), GIB);
        clock.advance(Duration.ofDays(2));

        MockShapeAAdapter restarted = new MockShapeAAdapter(new MockFaults(List.of()), clock);
        UsageSnapshot s = ok(restarted.queryUsage(card));

        assertThat(s.status()).isEqualTo(CardStatus.INACTIVE);
        assertThat(s.plans()).singleElement().satisfies(p -> {
            assertThat(p.upstreamPlanRef()).isEqualTo(card.upstreamRef());
            assertThat(p.remainingBytes()).isEqualTo(5 * GIB);
            assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-11-29T04:00:00Z"));
        });
        new MockUpstreamConsole(List.of(restarted)).activate(MockShapeAAdapter.ADAPTER_KEY, card.upstreamRef(), GIB);
        assertThat(ok(restarted.queryUsage(card)).status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void unknownUpstreamRefIsRejected() {
        assertThat(adapter.queryUsage(new CardRef(1, "8988800000000000000", "garbage"))).isInstanceOf(Outcome.Rejected.class);
        assertThat(adapter.queryUsage(new CardRef(1, "8988800000000000000", "MA-zz-zz-NOTAPKG"))).isInstanceOf(Outcome.Rejected.class);
    }
}
