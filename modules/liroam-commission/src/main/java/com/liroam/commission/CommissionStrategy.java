package com.liroam.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.liroam.commission.AgentDirectory.AgentChain;

/**
 * 佣金模式（domain-design §10）：只算一个订单行的佣金池，两级拆分由 {@link #split} 统一做。
 * 新模式 = 新增一个实现 Bean，策略表 {@code mode} 填它的 {@link #mode()}；账本不改。
 */
public interface CommissionStrategy {

    /**
     * @param baseFen 入账基数：快照实付 − 入账前已完成的退款
     * @param costFen 快照成本（人民币分），可空
     */
    record Context(String sourceType, String sourceLineRef, long skuBindingId, int qty, long baseFen, Long costFen, AgentChain chain) {
    }

    String mode();

    /** 后台下拉显示的名字。 */
    String title();

    /** 佣金池（分），不小于 0。 */
    long pool(Context ctx, CommissionPolicy policy);

    /**
     * 两级拆分，与壳 {@code DistributionCommissionCalculator.splitPool} 同一算式（一级 = 池 × directRate，二级 = 池 × inviteRate，
     * 各自舍入），金额以分计、HALF_UP；没有二级代理时二级为 0。
     */
    static long[] split(long poolFen, BigDecimal directRate, BigDecimal inviteRate, boolean hasInviter) {
        if (poolFen <= 0) {
            return new long[] {0, 0};
        }
        long direct = fen(poolFen, directRate);
        long invite = hasInviter ? fen(poolFen, inviteRate) : 0;
        return new long[] {direct, invite};
    }

    static long fen(long amountFen, BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(amountFen).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 实付抽成：池 = 基数 × poolRate。 */
    final class PaidRate implements CommissionStrategy {

        @Override
        public String mode() {
            return "PAID_RATE";
        }

        @Override
        public String title() {
            return "实付抽成";
        }

        @Override
        public long pool(Context ctx, CommissionPolicy policy) {
            return fen(ctx.baseFen(), policy.poolRate());
        }
    }

    /** 底价差价：池 = 基数 − 代理底价 × 数量（不低于 0）；没配底价的 SKU 池为 0。 */
    final class FloorDiff implements CommissionStrategy {

        private final CommissionPolicies policies;

        public FloorDiff(CommissionPolicies policies) {
            this.policies = policies;
        }

        @Override
        public String mode() {
            return "FLOOR_DIFF";
        }

        @Override
        public String title() {
            return "底价差价";
        }

        @Override
        public long pool(Context ctx, CommissionPolicy policy) {
            return policies.floorPriceFen(policy.id(), ctx.chain().direct(), ctx.skuBindingId())
                    .map(floor -> Math.max(0, ctx.baseFen() - floor * ctx.qty()))
                    .orElse(0L);
        }
    }

    /** 不计佣：用于对某 SKU / 等级关闭佣金。 */
    final class None implements CommissionStrategy {

        @Override
        public String mode() {
            return "NONE";
        }

        @Override
        public String title() {
            return "不计佣";
        }

        @Override
        public long pool(Context ctx, CommissionPolicy policy) {
            return 0;
        }
    }
}
