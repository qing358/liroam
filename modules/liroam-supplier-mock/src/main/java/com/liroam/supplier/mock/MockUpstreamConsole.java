package com.liroam.supplier.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.liroam.common.safety.Simulated;

/**
 * 模拟上游的操作台：供演示与冒烟把卡推到用户侧才会发生的状态（激活并用掉流量、到期），
 * 以便上层验证 READY→IN_USE、READY/IN_USE→EXPIRED。不经故障注入；进程重启后同样按 upstreamRef 重建再改。
 */
@Simulated
public final class MockUpstreamConsole {

    /** 卡的基础套餐（开卡时的那个）当前状态。 */
    public record BasePlan(String planRef, long quotaBytes, long usedBytes, Instant expiresAt) {
    }

    private final Map<String, MockUpstream> upstreams;

    public MockUpstreamConsole(List<? extends AbstractMockAdapter> adapters) {
        this.upstreams = adapters.stream().collect(Collectors.toUnmodifiableMap(AbstractMockAdapter::adapterKey, a -> a.upstream));
    }

    private MockUpstream upstream(String adapterKey) {
        MockUpstream upstream = upstreams.get(adapterKey);
        if (upstream == null) {
            throw new IllegalArgumentException("没有名为 " + adapterKey + " 的模拟供应商");
        }
        return upstream;
    }

    private static BasePlan base(MockUpstream.Card card) {
        MockUpstream.Plan p = card.base();
        return new BasePlan(p.ref(), p.pkg().quotaBytes(), p.usedBytes(), p.expiresAt());
    }

    /** 卡已激活，基础套餐已用 {@code usedBytes}（覆盖为该值，截在 0～额度之间）。 */
    public BasePlan activate(String adapterKey, String upstreamRef, long usedBytes) {
        return base(upstream(adapterKey).markInUse(upstreamRef, usedBytes));
    }

    /** 卡上全部套餐到期（无论是否激活过）。 */
    public BasePlan expire(String adapterKey, String upstreamRef) {
        return base(upstream(adapterKey).markExpired(upstreamRef));
    }

    /** 清空该家模拟上游的全部卡与流水（相当于上游重启）。 */
    public void reset(String adapterKey) {
        upstream(adapterKey).reset();
    }

    /** 上游视角下某 requestNo 实际开卡次数。 */
    public int effectiveProvisions(String adapterKey, String requestNo) {
        return upstream(adapterKey).provisions(requestNo);
    }
}
