package com.liroam.supplier.spi.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.LookupQuery;
import com.liroam.supplier.spi.LookupQuery.WriteOperation;
import com.liroam.supplier.spi.LookupResult;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.StatusCommand;
import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.SupplierCapabilities;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;
import com.liroam.supplier.spi.SupplierCapabilities.InventoryMode;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.TopUpResult;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.contract.UpstreamFixture.ExpectedUsage;
import com.liroam.supplier.spi.contract.UpstreamFixture.Failure;
import com.liroam.supplier.spi.contract.UpstreamFixture.SignedCallback;

/**
 * 供应商适配契约套件（domain-design §14 CT-SUP-01～08；CT-SUP-09 在架构检查）。
 * 每个适配器（含 mock）写一个子类，提供适配器与上游钩子；断言只按能力声明分支，不按供应商分支。
 */
public abstract class SupplierAdapterContractTest {

    private static final AtomicLong CARD_IDS = new AtomicLong(1000);

    protected abstract SupplierAdapter adapter();

    protected abstract UpstreamFixture upstream();

    private SupplierCapabilities caps;

    @BeforeEach
    void resetUpstream() {
        upstream().reset();
        caps = adapter().capabilities();
    }

    private static String requestNo(String type) {
        return type + "-" + CARD_IDS.incrementAndGet() + "-0-1";
    }

    private ProvisionCommand provisionCommand(String requestNo) {
        String iccid = caps.inventoryMode() == InventoryMode.POOL_REQUIRED ? upstream().idleIccid() : null;
        return new ProvisionCommand(requestNo, CARD_IDS.incrementAndGet(), iccid, upstream().baseOfferingCode(), null);
    }

    private static <T> T ok(Outcome<T> outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        return ((Outcome.Ok<T>) outcome).value();
    }

    private CardRef provisionedCard() {
        ProvisionCommand command = provisionCommand(requestNo("PROVISION"));
        ProvisionResult result = ok(adapter().provision(command));
        return new CardRef(command.cardAssetId(), result.iccid(), result.upstreamRef());
    }

    @Test
    void ct_sup_01_unsupportedOperationsAreRejectedNotThrown() {
        assertThat(caps.usageDimensions()).isNotEmpty();
        assertThat(caps.webhook()).isEqualTo(adapter().webhook().isPresent());
        CardRef card = provisionedCard();

        EnumSet<StatusOp> unsupported = EnumSet.allOf(StatusOp.class);
        unsupported.removeAll(caps.statusOps());
        for (StatusOp op : unsupported) {
            assertThatCode(() -> adapter().changeStatus(new StatusCommand(requestNo(op.name()), card, op))).doesNotThrowAnyException();
            assertThat(adapter().changeStatus(new StatusCommand(requestNo(op.name()), card, op))).as(op.name())
                    .isInstanceOf(Outcome.Rejected.class)
                    .extracting(o -> ((Outcome.Rejected<?>) o).code()).isEqualTo(Outcome.UNSUPPORTED);
        }
        if (!caps.credentialChannels().contains(CredentialChannel.API)) {
            assertThat(adapter().fetchCredential(card)).isInstanceOf(Outcome.Rejected.class)
                    .extracting(o -> ((Outcome.Rejected<?>) o).code()).isEqualTo(Outcome.UNSUPPORTED);
        } else {
            assertThat(ok(adapter().fetchCredential(card)).payload()).isNotBlank();
        }
    }

    @Test
    void ct_sup_02_provisionReturnsCardIdentity() {
        ProvisionCommand command = provisionCommand(requestNo("PROVISION"));

        ProvisionResult result = ok(adapter().provision(command));

        assertThat(result.iccid()).isNotBlank();
        assertThat(result.upstreamRef()).isNotBlank();
        if (caps.inventoryMode() == InventoryMode.POOL_REQUIRED) {
            assertThat(result.iccid()).isEqualTo(command.iccid());
        }
    }

    @Test
    void ct_sup_03_replayDoesNotProvisionTwice() {
        ProvisionCommand command = provisionCommand(requestNo("PROVISION"));
        ProvisionResult first = ok(adapter().provision(command));
        CardRef card = new CardRef(command.cardAssetId(), first.iccid(), first.upstreamRef());

        if (caps.idempotencyKey()) {
            ProvisionResult replay = ok(adapter().provision(command));
            assertThat(replay.iccid()).isEqualTo(first.iccid());
            assertThat(upstream().effectiveProvisions(command.requestNo())).isEqualTo(1);
        } else {
            assertThat(ok(adapter().lookup(new LookupQuery(command.requestNo(), WriteOperation.PROVISION, card))).effect())
                    .isEqualTo(LookupResult.Effect.EFFECTIVE);
            ProvisionCommand neverSent = provisionCommand(requestNo("PROVISION"));
            CardRef untouched = new CardRef(neverSent.cardAssetId(), neverSent.iccid(), null);
            assertThat(ok(adapter().lookup(new LookupQuery(neverSent.requestNo(), WriteOperation.PROVISION, untouched))).effect())
                    .isEqualTo(LookupResult.Effect.NOT_EFFECTIVE);
        }
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void ct_sup_04_failuresAreClassified(Failure failure) {
        upstream().failNext(failure);

        Outcome<ProvisionResult> outcome = adapter().provision(provisionCommand(requestNo("PROVISION")));

        Class<?> expected = switch (failure) {
            case CONNECT_FAILED, THROTTLED, SERVER_ERROR -> Outcome.Retryable.class;
            case BAD_REQUEST -> Outcome.Rejected.class;
            case TIMEOUT_AFTER_SEND, UNREGISTERED_CODE -> Outcome.Unknown.class;
        };
        assertThat(outcome).as(failure.name()).isInstanceOf(expected);
        if (failure == Failure.THROTTLED) {
            assertThat(((Outcome.Retryable<?>) outcome).retryAfter()).as("限流须带 retryAfter").isNotNull();
        }
    }

    @Test
    void ct_sup_05_usageIsNormalizedAndKeepsRawValues() {
        CardRef card = provisionedCard();
        ExpectedUsage expected = upstream().seedUsage(card);

        UsageSnapshot snapshot = ok(adapter().queryUsage(card));

        assertThat(snapshot.status()).isNotNull();
        assertThat(snapshot.rawJson()).isNotBlank();
        assertThat(snapshot.plans()).anySatisfy(plan -> {
            assertThat(plan.remainingBytes()).isEqualTo(expected.remainingBytes());
            assertThat(plan.expiresAt()).isEqualTo(expected.expiresAt());
            assertThat(plan.expiresAtRaw()).isEqualTo(expected.expiresAtRaw());
        });
    }

    @Test
    void ct_sup_06_addPlanLandsOnTheTargetCardOnly() {
        CardRef target = provisionedCard();
        CardRef other = provisionedCard();
        List<UsageSnapshot.PlanUsage> otherBefore = ok(adapter().queryUsage(other)).plans();

        TopUpResult result = ok(adapter().addPlan(new TopUpCommand(requestNo("ADD_PLAN"), target, upstream().topUpOfferingCode(), null)));

        assertThat(result.upstreamPlanRef()).isNotBlank();
        assertThat(ok(adapter().queryUsage(target)).plans()).extracting(UsageSnapshot.PlanUsage::upstreamPlanRef).contains(result.upstreamPlanRef());
        assertThat(ok(adapter().queryUsage(other)).plans()).isEqualTo(otherBefore);
    }

    @Test
    void ct_sup_07_declaredStatusOperationsSucceed() {
        CardRef card = provisionedCard();
        for (StatusOp op : List.of(StatusOp.SUSPEND, StatusOp.RESUME, StatusOp.TERMINATE)) {
            if (caps.statusOps().contains(op)) {
                assertThat(adapter().changeStatus(new StatusCommand(requestNo(op.name()), card, op))).as(op.name()).isInstanceOf(Outcome.Ok.class);
            }
        }
    }

    @Test
    void ct_sup_08_webhookVerifiesSignatureAndYieldsSameSnapshotShape() {
        assumeTrue(caps.webhook(), "未声明 webhook");
        CardRef card = provisionedCard();
        SignedCallback valid = upstream().validCallback(card);
        SignedCallback tampered = upstream().tamperedCallback(card);

        UsageSnapshot snapshot = ok(adapter().webhook().orElseThrow().parse(valid.headers(), valid.body()));

        assertThat(snapshot.cardUpstreamRef()).isEqualTo(card.upstreamRef());
        assertThat(snapshot.plans()).isNotEmpty();
        assertThat(adapter().webhook().orElseThrow().parse(tampered.headers(), tampered.body())).isInstanceOf(Outcome.Rejected.class);
    }
}
