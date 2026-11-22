package com.liroam.supplier.adapter.quadcell;

import org.junit.jupiter.api.AfterAll;

import com.liroam.supplier.spi.SupplierAdapter;
import com.liroam.supplier.spi.contract.SupplierAdapterContractTest;
import com.liroam.supplier.spi.contract.UpstreamFixture;

/** 与两家 mock 同一套契约（CT-SUP-01～08），上游为按文档报文回放的桩。 */
class QuadcellContractTest extends SupplierAdapterContractTest {

    private static final QuadcellStubUpstream UPSTREAM = QuadcellStubUpstream.start();
    private static final QuadcellAdapter ADAPTER = new QuadcellAdapter(UPSTREAM.properties());

    @AfterAll
    static void stop() {
        UPSTREAM.close();
    }

    @Override
    protected SupplierAdapter adapter() {
        return ADAPTER;
    }

    @Override
    protected UpstreamFixture upstream() {
        return UPSTREAM;
    }
}
