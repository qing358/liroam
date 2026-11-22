package com.liroam.fulfillment.testing;

import java.util.Set;

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
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.TopUpResult;
import com.liroam.supplier.spi.UsageSnapshot;

/** 包一层桩适配器，可临时收窄声明的状态操作（模拟不支持终止/停用的供应商）。 */
public final class NarrowableAdapter implements SupplierAdapter {

    private final SupplierAdapter delegate;
    private volatile Set<StatusOp> statusOps;

    NarrowableAdapter(SupplierAdapter delegate) {
        this.delegate = delegate;
    }

    public void statusOps(Set<StatusOp> ops) {
        this.statusOps = ops;
    }

    void reset() {
        this.statusOps = null;
    }

    @Override
    public SupplierCapabilities capabilities() {
        SupplierCapabilities c = delegate.capabilities();
        if (statusOps == null) {
            return c;
        }
        return new SupplierCapabilities(c.inventoryMode(), c.credentialChannels(), c.usageDimensions(), statusOps, c.topUpMode(), c.kycQuery(),
                c.webhook(), c.idempotencyKey(), c.rateLimitPerMinute());
    }

    @Override
    public Outcome<ProvisionResult> provision(ProvisionCommand command) {
        return delegate.provision(command);
    }

    @Override
    public Outcome<CredentialResult> fetchCredential(CardRef card) {
        return delegate.fetchCredential(card);
    }

    @Override
    public Outcome<UsageSnapshot> queryUsage(CardRef card) {
        return delegate.queryUsage(card);
    }

    @Override
    public Outcome<TopUpResult> addPlan(TopUpCommand command) {
        return delegate.addPlan(command);
    }

    @Override
    public Outcome<StatusResult> changeStatus(StatusCommand command) {
        return delegate.changeStatus(command);
    }

    @Override
    public Outcome<LookupResult> lookup(LookupQuery query) {
        return delegate.lookup(query);
    }
}
