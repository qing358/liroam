package com.liroam.supplier.mock;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

import com.liroam.supplier.mock.MockFaults.Operation;
import com.liroam.supplier.mock.MockUpstream.Reply;
import com.liroam.supplier.mock.MockUpstream.WireCard;
import com.liroam.supplier.mock.MockUpstream.WirePlan;
import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.LookupQuery;
import com.liroam.supplier.spi.LookupResult;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.StatusCommand;
import com.liroam.supplier.spi.StatusResult;
import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.TopUpResult;
import com.liroam.supplier.spi.UsageSnapshot;
import com.liroam.supplier.spi.UsageSnapshot.CardStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;

/**
 * 两家模拟适配器的共用翻译：上游码 → 四态的映射表、单位与时间归一、按能力声明拒绝不支持的操作。
 * 形状差异（开卡方式、交码、回调、查档口径）留在子类。
 */
public abstract class AbstractMockAdapter implements SupplierAdapter {

    final MockUpstream upstream;

    AbstractMockAdapter(MockUpstream upstream) {
        this.upstream = upstream;
    }

    /** Bean 名，即 {@code lr_supplier.adapter_key}。 */
    public abstract String adapterKey();

    /** 上游限流时建议的等待。 */
    abstract Duration throttleRetryAfter();

    final <R, T> Outcome<T> call(Supplier<Reply<R>> request, Function<R, T> mapper) {
        Reply<R> reply;
        try {
            reply = request.get();
        } catch (MockUpstream.NotSent e) {
            return Outcome.retryable("CONNECT_FAILED", null);
        } catch (MockUpstream.TimedOutAfterSend e) {
            return Outcome.unknown("READ_TIMEOUT");
        }
        return switch (reply.code()) {
            case MockUpstream.OK -> Outcome.ok(mapper.apply(reply.body()));
            case MockUpstream.THROTTLED -> Outcome.retryable("THROTTLED", throttleRetryAfter());
            case MockUpstream.SERVER_ERROR -> Outcome.retryable("SERVER_ERROR", null);
            case MockUpstream.BAD_REQUEST -> Outcome.rejected(reply.code(), "上游拒绝：参数或状态错误");
            case MockUpstream.CARD_NOT_FOUND -> Outcome.rejected(reply.code(), "上游查无此卡");
            case MockUpstream.ICCID_IN_USE -> Outcome.rejected(reply.code(), "该 ICCID 在上游已有订阅");
            case MockUpstream.CARD_TERMINATED -> Outcome.rejected(reply.code(), "卡已在上游终止");
            default -> Outcome.unknown("UNREGISTERED_CODE " + reply.code());
        };
    }

    static <T> Outcome<T> badPackage(String code) {
        return Outcome.rejected("BAD_PACKAGE", "套餐码须为 {GB}GB-{天数}D（可选 -{分钟}MIN、-{条数}SMS）：" + code);
    }

    final CardStatus status(String code) {
        CardStatus status = upstream.wire().status(code);
        if (status == null) {
            throw new IllegalStateException(adapterKey() + " 未登记的卡状态码 " + code);
        }
        return status;
    }

    final PlanUsage plan(WirePlan p) {
        WireFormat wire = upstream.wire();
        return new PlanUsage(p.ref(), wire.toBytes(p.quota()), wire.toBytes(p.used()), wire.toBytes(p.quota() - p.used()),
                p.voiceSeconds(), p.sms(), wire.parseTime(p.startsAt()), wire.parseTime(p.expiresAt()), p.startsAt(), p.expiresAt());
    }

    private UsageSnapshot snapshot(WireCard card) {
        return new UsageSnapshot(card.ref(), status(card.status()), card.plans().stream().map(this::plan).toList(), card.json());
    }

    static LookupResult lookupResult(String ref) {
        return ref.isEmpty() ? new LookupResult(LookupResult.Effect.NOT_EFFECTIVE, null) : new LookupResult(LookupResult.Effect.EFFECTIVE, ref);
    }

    @Override
    public Outcome<CredentialResult> fetchCredential(CardRef card) {
        if (!capabilities().credentialChannels().contains(CredentialChannel.API)) {
            return Outcome.rejected(Outcome.UNSUPPORTED, "fetchCredential");
        }
        return call(() -> upstream.credential(card.upstreamRef(), card.iccid()), lpa -> new CredentialResult(CredentialResult.Kind.LPA, lpa));
    }

    @Override
    public Outcome<UsageSnapshot> queryUsage(CardRef card) {
        return call(() -> upstream.query(card.upstreamRef(), card.iccid()), this::snapshot);
    }

    @Override
    public Outcome<TopUpResult> addPlan(TopUpCommand command) {
        return MockPackage.parse(command.offeringCode())
                .map(pkg -> call(() -> upstream.addPlan(command.requestNo(), command.card().upstreamRef(), command.card().iccid(), pkg), TopUpResult::new))
                .orElseGet(() -> badPackage(command.offeringCode()));
    }

    @Override
    public Outcome<StatusResult> changeStatus(StatusCommand command) {
        if (!capabilities().statusOps().contains(command.op())) {
            return Outcome.rejected(Outcome.UNSUPPORTED, command.op().name());
        }
        Operation op = Operation.valueOf(command.op().name());
        return call(() -> upstream.changeStatus(command.requestNo(), command.card().upstreamRef(), command.card().iccid(), op),
                card -> new StatusResult(status(card.status())));
    }

    @Override
    public Outcome<LookupResult> lookup(LookupQuery query) {
        return call(() -> upstream.lookupRequest(query.requestNo()), AbstractMockAdapter::lookupResult);
    }
}
