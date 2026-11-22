package com.liroam.supplier.spi.contract.stub;

import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.contract.SupplierAdapterContractTest;
import com.liroam.supplier.spi.contract.UpstreamFixture;

class PoolImportStubContractTest extends SupplierAdapterContractTest {

    private final StubUpstream upstream = new StubUpstream(StubShape.POOL_IMPORT);
    private final ContractStubAdapter adapter = new ContractStubAdapter(StubShape.POOL_IMPORT, upstream);

    @Override
    protected SupplierAdapter adapter() {
        return adapter;
    }

    @Override
    protected UpstreamFixture upstream() {
        return upstream;
    }
}
