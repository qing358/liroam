package com.liroam.commission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.liroam.commission.AgentDirectory.Agent;
import com.liroam.commission.AgentDirectory.AgentChain;
import com.liroam.commission.CommissionPolicies.PolicyInput;
import com.liroam.commission.config.LiroamCommissionAutoConfiguration;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.refund.RefundCases;
import com.liroam.refund.RefundRules;
import com.liroam.refund.config.LiroamRefundAutoConfiguration;

/** 佣金集成测试装配：履约两形状假上游 + 真退款模块 + 佣金模块；代理链用内存替身。 */
final class CommissionFixture implements AutoCloseable {

    static final String OP = "manager:admin";
    static final Agent A1 = new Agent("D1", "m-agent-1", "一级代理", "1");
    static final Agent A2 = new Agent("D2", "m-agent-2", "二级代理", "2");

    static final class FakeAgents implements AgentDirectory {

        final Map<String, AgentChain> orders = new HashMap<>();
        final Map<String, Agent> members = new HashMap<>();

        @Override
        public Optional<AgentChain> chainOfOrder(String orderSn) {
            return Optional.ofNullable(orders.get(orderSn));
        }

        @Override
        public Optional<Agent> agentOfMember(String memberId) {
            return Optional.ofNullable(members.get(memberId));
        }

        @Override
        public Map<String, Agent> agents(Collection<String> agentRefs) {
            return members.values().stream().filter(a -> agentRefs.contains(a.agentRef())).collect(Collectors.toMap(Agent::agentRef, a -> a));
        }

        void reset() {
            orders.clear();
            members.clear();
            members.put(A1.memberId(), A1);
            members.put(A2.memberId(), A2);
        }
    }

    final FulfillmentHarness h;
    final TestCatalog c;
    final FakeAgents agents;
    final CommissionPolicies policies;
    final CommissionAccruals accruals;
    final CommissionLedger ledger;
    final Withdrawals withdrawals;
    final RefundCases refunds;

    private CommissionFixture(FulfillmentHarness h, FakeAgents agents) {
        this.h = h;
        this.c = h.catalog;
        this.agents = agents;
        this.policies = h.bean(CommissionPolicies.class);
        this.accruals = h.bean(CommissionAccruals.class);
        this.ledger = h.bean(CommissionLedger.class);
        this.withdrawals = h.bean(Withdrawals.class);
        this.refunds = h.bean(RefundCases.class);
    }

    static CommissionFixture start(String module) {
        FakeAgents agents = new FakeAgents();
        FulfillmentHarness h = FulfillmentHarness.start(module, Map.of(), context -> {
            context.registerBean(AgentDirectory.class, () -> agents);
            context.register(LiroamCommissionAutoConfiguration.class, LiroamRefundAutoConfiguration.class);
        });
        return new CommissionFixture(h, agents);
    }

    void reset() {
        h.reset();
        List.of("lr_commission_entry", "lr_commission_accrual", "lr_agent_balance", "lr_withdrawal", "lr_agent_floor_price", "lr_commission_policy",
                "lr_refund_case", "lr_order_line_snapshot", "lr_refund_rule").forEach(t -> h.jdbc.update("DELETE FROM " + t));
        RefundRules.seedDevDefaults(h.bean(DataSource.class));
        agents.reset();
    }

    /** 全局实付抽成：池 = 实付 × poolRate，一级 directRate、二级 inviteRate。 */
    CommissionPolicy paidRate(String poolRate, String direct, String invite, int settleDays) {
        return policies.save(new PolicyInput("GLOBAL_DEFAULT", "PAID_RATE", "GLOBAL", null, new BigDecimal(poolRate), new BigDecimal(direct),
                new BigDecimal(invite), false, settleDays, "DELIVERED", true, null), OP);
    }

    /** 自营订单行：快照实付 + 代理链 + 收单；返回履约行 id。 */
    long shopLine(String order, long skuBindingId, int qty, long paidFen, AgentChain chain) {
        snapshot("SHOP", order + "-1", skuBindingId, paidFen, qty);
        if (chain != null) {
            agents.orders.put(order, chain);
        }
        return h.acceptShop(order, order + "-1", skuBindingId, qty, "m-buyer");
    }

    /** 供应商 A 一张已交码未激活的卡；返回卡 id。 */
    long deliveredCard(String order, long paidFen, AgentChain chain) {
        h.importPoolA(1, true);
        long line = shopLine(order, c.skuANew, 1, paidFen, chain);
        h.drain();
        return h.cardOfLine(line);
    }

    void snapshot(String sourceType, String sourceLineRef, long skuBindingId, long paidFen, int qty) {
        h.jdbc.update("""
                INSERT INTO lr_order_line_snapshot (source_type, source_line_ref, sku_binding_id, supplier_id, card_product_id, region_id,
                                                    region_group_id, usage_region_text, unit_price_fen, paid_fen, qty)
                SELECT ?, ?, id, supplier_id, card_product_id, region_id, region_group_id, '测试区域', ?, ?, ? FROM lr_sku_binding WHERE id = ?
                """, sourceType, sourceLineRef, paidFen / qty, paidFen, qty, skuBindingId);
    }

    /** 某代理按类型的账本行金额（按 id 顺序）。 */
    List<Long> amounts(String agentRef, String type) {
        return h.jdbc.queryForList("SELECT amount_fen FROM lr_commission_entry WHERE agent_ref = ? AND type = ? ORDER BY id", Long.class, agentRef,
                type);
    }

    List<String> statuses(String agentRef, String type) {
        return h.jdbc.queryForList("SELECT status FROM lr_commission_entry WHERE agent_ref = ? AND type = ? ORDER BY id", String.class, agentRef,
                type);
    }

    void advance(java.time.Duration d) {
        h.clock.advance(d);
    }

    Instant now() {
        return h.clock.instant();
    }

    @Override
    public void close() {
        h.close();
    }
}
