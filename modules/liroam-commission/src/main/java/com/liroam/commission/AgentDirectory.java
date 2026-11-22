package com.liroam.commission;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 代理（壳分销员，H9 只读）端口：实现在 {@code liroam-shell-bridge}，读 {@code li_distribution} 与 {@code li_order.distribution_id}。
 * 只返回审核通过的分销员；{@code agentRef} = {@code li_distribution.id}，{@code grade} = 壳分销等级。
 */
public interface AgentDirectory {

    record Agent(String agentRef, String memberId, String name, String grade) {
    }

    /** @param inviter 一级代理的上级，没有为空 */
    record AgentChain(Agent direct, Agent inviter) {
    }

    /** 自营订单成交时挂的分销员（一级）及其上级（二级）。 */
    Optional<AgentChain> chainOfOrder(String orderSn);

    Optional<Agent> agentOfMember(String memberId);

    Map<String, Agent> agents(Collection<String> agentRefs);
}
