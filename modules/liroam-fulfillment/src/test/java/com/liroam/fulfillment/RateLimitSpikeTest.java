package com.liroam.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.common.testing.ItDatabase;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.mysql.Bucket4jMySQL;

/**
 * spike（domain-design §6.3 / §17）：bucket4j_jdk17-mysql 8.22.0 在 connector-j 9.6 + MySQL 8.0 上，
 * 两个「实例」（各自的连接源与 ProxyManager）共享同一个供应商令牌桶。正式接入随指令 worker（P2）。
 */
class RateLimitSpikeTest {

    private static final int CAPACITY = 10;

    private static ProxyManager<String> proxyManager(DataSource ds) {
        return Bucket4jMySQL.selectForUpdateBasedBuilder(ds)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("lr_spike_rate_bucket")
                .build();
    }

    private static BucketConfiguration perSupplier() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(CAPACITY).refillIntervally(CAPACITY, Duration.ofHours(1)).build())
                .build();
    }

    @Test
    void twoInstancesShareOneSupplierBucket() throws Exception {
        assertThat(com.mysql.cj.Constants.CJ_VERSION).startsWith("9.6");
        DataSource instanceA = ItDatabase.fresh("fulfillment_ratelimit");
        new JdbcTemplate(instanceA).execute("CREATE TABLE lr_spike_rate_bucket (id VARCHAR(64) PRIMARY KEY, state BLOB, expires_at BIGINT)");
        DataSource instanceB = ItDatabase.reopen("fulfillment_ratelimit");

        BucketProxy bucketA = proxyManager(instanceA).builder().build("SUP_A", RateLimitSpikeTest::perSupplier);
        BucketProxy bucketB = proxyManager(instanceB).builder().build("SUP_A", RateLimitSpikeTest::perSupplier);
        BucketProxy otherSupplier = proxyManager(instanceB).builder().build("SUP_B", RateLimitSpikeTest::perSupplier);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                BucketProxy bucket = i % 2 == 0 ? bucketA : bucketB;
                calls.add(() -> bucket.tryConsume(1));
            }
            int granted = 0;
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                granted += f.get() ? 1 : 0;
            }
            assertThat(granted).isEqualTo(CAPACITY);
        } finally {
            pool.shutdownNow();
        }
        assertThat(bucketA.getAvailableTokens()).isZero();
        assertThat(bucketB.getAvailableTokens()).isZero();
        assertThat(otherSupplier.tryConsume(1)).as("不同供应商的桶互不影响").isTrue();
    }
}
