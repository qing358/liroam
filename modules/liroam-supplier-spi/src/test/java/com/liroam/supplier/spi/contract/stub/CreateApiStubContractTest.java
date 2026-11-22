package com.liroam.supplier.spi.contract.stub;

import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.contract.SupplierAdapterContractTest;
import com.liroam.supplier.spi.contract.UpstreamFixture;

class CreateApiStubContractTest extends SupplierAdapterContractTest {

    private final StubUpstream upstream = new StubUpstream(StubShape.CREATE_API);
    private final ContractStubAdapter adapter = new ContractStubAdapter(StubShape.CREATE_API, upstream);

    @Override
    protected SupplierAdapter adapter() {
        return adapter;
    }

    @Override
    protected UpstreamFixture upstream() {
        return upstream;
    }
}
