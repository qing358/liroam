package com.liroam.supplier.spi.contract.stub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

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
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.TopUpResult;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;
import com.liroam.supplier.spi.WebhookParser;
import com.liroam.supplier.spi.contract.stub.StubUpstream.Reply;

/** 只在 test：验证契约套件本身的最小桩（P2 的 mock 供应商另起模块）。 */
public final class ContractStubAdapter implements SupplierAdapter {

    private final StubShape shape;
    private final StubUpstream upstream;

    public ContractStubAdapter(StubShape shape, StubUpstream upstream) {
        this.shape = shape;
        this.upstream = upstream;
    }

    @Override
    public SupplierCapabilities capabilities() {
        return shape.capabilities();
    }

    private <R, T> Outcome<T> call(Supplier<Reply<R>> request, Function<R, T> mapper) {
        Reply<R> reply;
        try {
            reply = request.get();
        } catch (StubUpstream.NotSent e) {
            return Outcome.retryable("CONNECT_FAILED", null);
        } catch (StubUpstream.TimedOutAfterSend e) {
            return Outcome.unknown("READ_TIMEOUT");
        }
        return switch (reply.code()) {
            case StubUpstream.OK -> Outcome.ok(mapper.apply(reply.body()));
            case StubUpstream.THROTTLED -> Outcome.retryable("THROTTLED", Duration.ofSeconds(1));
            case StubUpstream.SERVER_ERROR -> Outcome.retryable("SERVER_ERROR", null);
            case StubUpstream.BAD_PARAM -> Outcome.rejected(StubUpstream.BAD_PARAM, "参数或状态错误");
            default -> Outcome.unknown("UNREGISTERED_CODE " + reply.code());
        };
    }

    @Override
    public Outcome<ProvisionResult> provision(ProvisionCommand command) {
        return call(() -> upstream.activate(command.requestNo(), command.iccid(), capabilities().idempotencyKey()),
                ref -> new ProvisionResult(upstream.card(ref).iccid, ref, Optional.empty()));
    }

    @Override
    public Outcome<CredentialResult> fetchCredential(CardRef card) {
        if (!capabilities().credentialChannels().contains(CredentialChannel.API)) {
            return Outcome.rejected(Outcome.UNSUPPORTED, "fetchCredential");
        }
        return call(() -> upstream.credential(card.upstreamRef()), lpa -> new CredentialResult(CredentialResult.Kind.LPA, lpa));
    }

    @Override
    public Outcome<UsageSnapshot> queryUsage(CardRef card) {
        return call(() -> upstream.query(card.upstreamRef()), this::snapshot);
    }

    private UsageSnapshot snapshot(StubUpstream.Card card) {
        List<PlanUsage> plans = card.plans.stream().map(this::plan).toList();
        String raw = "{\"ref\":\"" + card.ref + "\",\"status\":\"" + card.status + "\",\"plans\":" + card.plans.size() + "}";
        return new UsageSnapshot(card.ref, CardStatus.valueOf(card.status), plans, raw);
    }

    private PlanUsage plan(StubUpstream.Plan plan) {
        long unit = upstream.bytesPerUnit();
        return new PlanUsage(plan.ref(), plan.quotaUnits() * unit, plan.usedUnits() * unit,
                (plan.quotaUnits() - plan.usedUnits()) * unit, null, null,
                null, upstream.parseTime(plan.expiresRaw()), null, plan.expiresRaw());
    }

    @Override
    public Outcome<TopUpResult> addPlan(TopUpCommand command) {
        return call(() -> upstream.addPackage(command.card().upstreamRef(), command.offeringCode()), TopUpResult::new);
    }

    @Override
    public Outcome<StatusResult> changeStatus(StatusCommand command) {
        if (!capabilities().statusOps().contains(command.op())) {
            return Outcome.rejected(Outcome.UNSUPPORTED, command.op().name());
        }
        String target = switch (command.op()) {
            case SUSPEND -> "SUSPENDED";
            case RESUME -> "ACTIVE";
            case TERMINATE -> "TERMINATED";
        };
        return call(() -> upstream.setStatus(command.card().upstreamRef(), target), s -> new StatusResult(CardStatus.valueOf(s)));
    }

    @Override
    public Outcome<LookupResult> lookup(LookupQuery query) {
        return call(() -> upstream.activationRef(query.requestNo()), ref -> ref.isEmpty()
                ? new LookupResult(LookupResult.Effect.NOT_EFFECTIVE, null)
                : new LookupResult(LookupResult.Effect.EFFECTIVE, ref));
    }

    @Override
    public Optional<WebhookParser> webhook() {
        if (!capabilities().webhook()) {
            return Optional.empty();
        }
        return Optional.of((headers, body) -> {
            String signature = headers.getOrDefault(StubUpstream.SIGNATURE_HEADER, "");
            if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), StubUpstream.sign(body).getBytes(StandardCharsets.UTF_8))) {
                return Outcome.rejected("BAD_SIGNATURE", "回调验签失败");
            }
            String[] f = new String(body, StandardCharsets.UTF_8).split("\\|");
            StubUpstream.Plan plan = new StubUpstream.Plan(f[1], Long.parseLong(f[2]), Long.parseLong(f[3]), f[4]);
            String raw = new String(body, StandardCharsets.UTF_8);
            return Outcome.ok(new UsageSnapshot(f[0], CardStatus.ACTIVE, List.of(plan(plan)), raw));
        });
    }
}
