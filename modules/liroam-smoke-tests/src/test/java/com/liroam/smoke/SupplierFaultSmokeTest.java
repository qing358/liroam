package com.liroam.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.supplier.mock.MockFaults;
import com.liroam.supplier.mock.MockFaults.Failure;
import com.liroam.supplier.mock.MockFaults.Operation;
import com.liroam.supplier.mock.MockShapeBAdapter;

/**
 * P2 冒烟 V10：mock 故障注入下的指令执行。形状 A 靠 mock 默认规则（ICCID 尾号 9991 首次开卡限流、9992 首次开卡写后超时），
 * 形状 B 运行期注入一次开卡被拒。
 */
class SupplierFaultSmokeTest {

    private static final List<String> POOL_A = List.of("8988800000000009991", "8988800000000009992");

    private static SmokeHarness h;
    private static TestCatalog c;

    @BeforeAll
    static void start() {
        h = new SmokeHarness("smoke_supplier_faults");
        c = h.catalog;
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    private List<String> callLog(String column, long supplierId) {
        return h.jdbc.queryForList("SELECT " + column + " FROM lr_supplier_call_log WHERE supplier_id = ? ORDER BY id", String.class, supplierId);
    }

    @Test
    void v10_shapeAThrottleAndTimeoutRecover_shapeBRejectGoesToManualQueue() {
        h.importPoolA(POOL_A);
        h.bean(MockFaults.class).failNext(MockShapeBAdapter.ADAPTER_KEY, Operation.PROVISION, Failure.BAD_REQUEST);

        h.acceptShop("LO-FAULT-1", "LO-FAULT-1-A", c.skuANew, 2, "m-fault");
        h.acceptShop("LO-FAULT-1", "LO-FAULT-1-B", c.skuBNew, 1, "m-fault");
        h.drain();

        assertThat(h.jdbc.queryForList("SELECT status FROM lr_card_asset WHERE supplier_id = ?", String.class, c.supplierA))
                .as("A：限流退避重试、写后超时查档确认后都开通成功").containsExactly("PROVISIONED", "PROVISIONED");
        assertThat(h.jdbc.queryForList("SELECT status FROM lr_supplier_command WHERE type = 'PROVISION' AND supplier_id = ?", String.class,
                c.supplierA)).containsOnly("SUCCEEDED");
        assertThat(callLog("outcome", c.supplierA)).contains("THROTTLED", "UNKNOWN", "OK");
        assertThat(callLog("operation", c.supplierA)).as("写后超时走查档，不盲目重发开卡").contains("LOOKUP_PROVISION");

        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_asset WHERE supplier_id = ?", String.class, c.supplierB)).isEqualTo("EXCEPTION");
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_supplier_command WHERE supplier_id = ?", String.class, c.supplierB)).isEqualTo("MANUAL");
        assertThat(callLog("outcome", c.supplierB)).containsExactly("REJECTED");
        assertThat(h.count("lr_manual_task WHERE kind = 'COMMAND_FAILED' AND status = 'OPEN'")).as("B：进人工待办").isEqualTo(1);
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_fulfillment_line WHERE source_line_ref = 'LO-FAULT-1-B'", String.class))
                .isEqualTo("PARTIAL");
        assertThat(h.shell.calls()).as("整单未完成不回写壳").isEmpty();
    }
}
