package com.liroam.fulfillment.worker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.liroam.catalog.Supplier;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentSettings;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.mysql.Bucket4jMySQL;

/**
 * 每供应商一个全局令牌桶（domain-design §6.3），状态存 {@code lr_supplier_rate_bucket}，多实例共享计数。
 * 容量 = 每分钟调用数（{@code lr_supplier.rate_limit_per_min} 覆盖适配器声明），每分钟匀速回满。
 * 写优先：轮询/按需刷新只在余量高于 容量 × {@code poll.reserve_ratio} 时取令牌，留出的余量给写操作。
 * 桶 key 带容量，改容量即换新桶；时间取注入的 {@link Clock}，测试可拨表。
 */
public class SupplierRateLimiter {

    /** @param retryAfter 没取到令牌时，建议多久后再试 */
    public record Permit(boolean granted, Duration retryAfter) {

        static Permit ok() {
            return new Permit(true, Duration.ZERO);
        }
    }

    public enum Purpose { WRITE, READ }

    private final ProxyManager<String> buckets;
    private final Settings settings;
    private final Map<String, BucketProxy> proxies = new ConcurrentHashMap<>();

    public SupplierRateLimiter(DataSource dataSource, Settings settings, Clock clock) {
        this.settings = settings;
        this.buckets = Bucket4jMySQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("lr_supplier_rate_bucket")
                .idColumn("bucket_key")
                .stateColumn("state")
                .clientClock(clockMeter(clock))
                .build();
    }

    public static int capacity(Supplier supplier, int declaredPerMinute) {
        return Optional.ofNullable(supplier.rateLimitPerMin()).filter(v -> v > 0).orElse(declaredPerMinute);
    }

    public Permit acquire(Supplier supplier, int capacity, Purpose purpose) {
        BucketProxy bucket = bucket(supplier.id(), capacity);
        if (purpose == Purpose.READ) {
            long reserve = reserve(supplier.id(), capacity);
            long available = bucket.getAvailableTokens();
            if (available <= reserve) {
                return new Permit(false, refillTime(capacity, reserve + 1 - available));
            }
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Permit.ok();
        }
        return new Permit(false, Duration.ofNanos(Math.max(probe.getNanosToWaitForRefill(), 1_000_000L)));
    }

    public long available(long supplierId, int capacity) {
        return bucket(supplierId, capacity).getAvailableTokens();
    }

    private long reserve(long supplierId, int capacity) {
        BigDecimal ratio = settings.get(FulfillmentSettings.POLL_RESERVE_RATIO, SettingScope.supplier(supplierId));
        return BigDecimal.valueOf(capacity).multiply(ratio).setScale(0, RoundingMode.CEILING).longValue();
    }

    private static Duration refillTime(int capacity, long tokens) {
        return Duration.ofMillis(Math.max(1, Duration.ofMinutes(1).toMillis() * tokens / capacity));
    }

    private BucketProxy bucket(long supplierId, int capacity) {
        String key = "supplier:" + supplierId + ":" + capacity;
        return proxies.computeIfAbsent(key, k -> buckets.builder().build(k, () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, Duration.ofMinutes(1)).build())
                .build()));
    }

    private static TimeMeter clockMeter(Clock clock) {
        return new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                Instant now = clock.instant();
                return now.getEpochSecond() * 1_000_000_000L + now.getNano();
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }
}
