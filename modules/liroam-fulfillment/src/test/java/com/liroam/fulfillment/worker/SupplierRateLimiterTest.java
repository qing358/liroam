package com.liroam.fulfillment.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.catalog.Supplier;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.fulfillment.worker.SupplierRateLimiter.Purpose;

/** 每供应商 MySQL 令牌桶：多实例共享计数（CT-FUL-04）、写优先预留、容量覆盖、限流推迟不计失败。 */
class SupplierRateLimiterTest {

    private static FulfillmentHarness h;
    private static FulfillmentHarness other;
    private static TestCatalog c;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_ratelimit");
        other = h.secondInstance();
        c = h.catalog;
    }

    @AfterAll
    static void stop() {
        other.close();
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    private Supplier supplierB() {
        return h.bean(SupplierDirectory.class).find(c.supplierB).orElseThrow();
    }

    @Test
    void ct_ful_04_twoExecutorInstancesOnOneDatabaseShareTheSupplierBucket() {
        h.jdbc.update("UPDATE lr_supplier SET rate_limit_per_min = 3 WHERE id = ?", c.supplierB);
        h.acceptShop("O-RL", "O-RL-1", c.skuBNew, 5, "m1");
        h.bean(FulfillmentWorker.class).runOnce(10);
        CommandExecutor first = h.bean(CommandExecutor.class);
        CommandExecutor second = other.bean(CommandExecutor.class);

        for (int i = 0; i < 3; i++) {
            first.runOnce(1, "w1");
            second.runOnce(1, "w2");
        }

        assertThat(h.count("lr_supplier_call_log")).as("两实例合计只放行容量那么多次上游调用").isEqualTo(3);
        assertThat(h.count("lr_supplier_command WHERE type = 'PROVISION' AND status = 'PENDING' AND error_code = 'RATE_LIMIT' AND attempts = 0"))
                .as("被限流的推迟、不计失败").isEqualTo(2);

        h.clock.advance(Duration.ofSeconds(21));
        first.runOnce(10, "w1");
        second.runOnce(10, "w2");
        assertThat(h.count("lr_supplier_call_log")).as("3/min 每 20 秒回一个令牌").isEqualTo(4);
    }

    @Test
    void readsLeaveTheReserveForWrites() {
        SupplierRateLimiter limiter = h.bean(SupplierRateLimiter.class);
        Supplier b = supplierB();

        int reads = 0;
        while (limiter.acquire(b, 10, Purpose.READ).granted()) {
            reads++;
        }
        var deniedRead = limiter.acquire(b, 10, Purpose.READ);

        assertThat(reads).as("容量 10 × 预留 0.2 = 留 2 个给写").isEqualTo(8);
        assertThat(deniedRead.retryAfter()).isPositive();
        assertThat(limiter.acquire(b, 10, Purpose.WRITE).granted()).isTrue();
        assertThat(limiter.acquire(b, 10, Purpose.WRITE).granted()).isTrue();
        assertThat(limiter.acquire(b, 10, Purpose.WRITE).granted()).isFalse();
    }

    @Test
    void supplierRowOverridesDeclaredCapacity() {
        assertThat(SupplierRateLimiter.capacity(supplierB(), 600)).isEqualTo(600);
        h.jdbc.update("UPDATE lr_supplier SET rate_limit_per_min = 7 WHERE id = ?", c.supplierB);
        assertThat(SupplierRateLimiter.capacity(supplierB(), 600)).isEqualTo(7);
    }
}
