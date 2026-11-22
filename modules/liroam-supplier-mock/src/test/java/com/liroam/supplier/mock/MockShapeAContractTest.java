package com.liroam.supplier.mock;

import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.contract.SupplierAdapterContractTest;
import com.liroam.supplier.spi.contract.UpstreamFixture;

class MockShapeAContractTest extends SupplierAdapterContractTest {

    private final MockFaults faults = new MockFaults(MockSupplierProperties.defaultRules());
    private final MockShapeAAdapter adapter = new MockShapeAAdapter(faults, new MutableClock(MockUpstreamFixture.NOW));
    private final MockUpstreamFixture upstream = MockUpstreamFixture.shapeA(adapter, faults);

    @Override
    protected SupplierAdapter adapter() {
        return adapter;
    }

    @Override
    protected UpstreamFixture upstream() {
        return upstream;
    }
}
