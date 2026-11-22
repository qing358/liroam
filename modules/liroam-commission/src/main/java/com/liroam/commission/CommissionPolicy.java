package com.liroam.commission;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一个佣金策略版本（{@code lr_commission_policy}，只增）。比例都是 0～1 小数。
 *
 * @param poolRate    实付抽成的池比例（底价差价、不计佣模式不用）
 * @param directRate  池中归一级代理的比例
 * @param inviteRate  池中归二级代理的比例
 * @param settleDays  T+N：0 = 锚点即可提现；N ≥ 1 = 锚点所在自然日（东八区）后第 N 天零点
 */
public record CommissionPolicy(long id, String code, String mode, ScopeType scopeType, String scopeRef, BigDecimal poolRate, BigDecimal directRate,
        BigDecimal inviteRate, boolean includeChannelOrders, int settleDays, SettleAnchor settleAnchor, boolean withdrawApproval, int version,
        Instant effectiveFrom) {

    /** 选择优先级：SKU > 代理等级 > 全局。 */
    public enum ScopeType { SKU, AGENT_GRADE, GLOBAL }

    public enum SettleAnchor { DELIVERED, PAID }
}
