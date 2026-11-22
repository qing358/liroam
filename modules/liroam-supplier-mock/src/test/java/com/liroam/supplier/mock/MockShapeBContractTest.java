package com.liroam.supplier.mock;

import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.contract.SupplierAdapterContractTest;
import com.liroam.supplier.spi.contract.UpstreamFixture;

class MockShapeBContractTest extends SupplierAdapterContractTest {

    private static final String SECRET = "contract-test-secret";

    private final MockFaults faults = new MockFaults(MockSupplierProperties.defaultRules());
    private final MockShapeBAdapter adapter = new MockShapeBAdapter(faults, new MutableClock(MockUpstreamFixture.NOW), SECRET);
    private final MockUpstreamFixture upstream = MockUpstreamFixture.shapeB(adapter, faults, SECRET);

    @Override
    protected SupplierAdapter adapter() {
        return adapter;
    }

    @Override
    protected UpstreamFixture upstream() {
        return upstream;
    }
}
