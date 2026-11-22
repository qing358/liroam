package com.liroam.supplier.mock;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import com.liroam.common.safety.Simulated;
import com.liroam.supplier.spi.LookupQuery;
import com.liroam.supplier.spi.LookupResult;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.SupplierCapabilities;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;
import com.liroam.supplier.spi.SupplierCapabilities.InventoryMode;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.SupplierCapabilities.TopUpMode;
import com.liroam.supplier.spi.SupplierCapabilities.UsageDimension;

/**
 * 模拟供应商形状 A：池模式（开卡须带预置 ICCID）、交码走导入/人工、无幂等键（开卡查档按 ICCID）、无回调、
 * 可停用/恢复/终止、加包为同卡新增套餐、100 次/分钟；上游流量单位 MB、时间为东八区本地时间串。
 */
@Simulated
public final class MockShapeAAdapter extends AbstractMockAdapter {

    public static final String ADAPTER_KEY = "mock-shape-a";

    static final SupplierCapabilities CAPABILITIES = new SupplierCapabilities(
            InventoryMode.POOL_REQUIRED,
            Set.of(CredentialChannel.IMPORT, CredentialChannel.MANUAL),
            Set.of(UsageDimension.DATA, UsageDimension.DAILY_DATA),
            Set.of(StatusOp.SUSPEND, StatusOp.RESUME, StatusOp.TERMINATE),
            TopUpMode.ADD_PACKAGE,
            false, false, false, 100);

    public MockShapeAAdapter(MockFaults faults, Clock clock) {
        super(new MockUpstream(ADAPTER_KEY, "MA", WireFormat.MEGABYTES_LOCAL_TIME, false, faults, clock));
    }

    @Override
    public String adapterKey() {
        return ADAPTER_KEY;
    }

    @Override
    public SupplierCapabilities capabilities() {
        return CAPABILITIES;
    }

    /** 100 次/分钟的令牌约每 0.6 秒补一个。 */
    @Override
    Duration throttleRetryAfter() {
        return Duration.ofSeconds(1);
    }

    @Override
    public Outcome<ProvisionResult> provision(ProvisionCommand command) {
        Optional<MockPackage> pkg = MockPackage.parse(command.offeringCode());
        if (pkg.isEmpty()) {
            return badPackage(command.offeringCode());
        }
        if (command.iccid() == null || command.iccid().isBlank()) {
            return Outcome.rejected(MockUpstream.BAD_REQUEST, "池模式开卡须带 ICCID");
        }
        return call(() -> upstream.provision(command.requestNo(), command.iccid(), pkg.get()),
                card -> new ProvisionResult(card.iccid(), card.ref(), Optional.empty()));
    }

    /** 上游不认 requestNo：开卡是否生效只能按 ICCID 查卡上有无订阅。 */
    @Override
    public Outcome<LookupResult> lookup(LookupQuery query) {
        if (query.operation() != LookupQuery.WriteOperation.PROVISION) {
            return super.lookup(query);
        }
        String iccid = query.card() == null ? null : query.card().iccid();
        return call(() -> upstream.lookupIccid(query.requestNo(), iccid), AbstractMockAdapter::lookupResult);
    }
}
