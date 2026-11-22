package com.liroam.commission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.commission.AgentDirectory.Agent;
import com.liroam.commission.AgentDirectory.AgentChain;
import com.liroam.commission.CommissionPolicy.SettleAnchor;
import com.liroam.common.time.UtcColumns;

/**
 * 订单行入账（domain-design §10）：扫描「已有卡交码」或「已完成（加包）」且还没判定过的履约行，每行在自己的事务里判定一次，
 * 结果记 {@code lr_commission_accrual}（行级幂等；无代理 / 无策略 / 排除也记，之后不再扫）。
 * 基数 = 快照实付 − 入账前已完成退款；入账写 ACCRUE，到 {@code settleable_at} 前为 PENDING。旅行卡不经壳分销奖励（H10）。
 */
public class CommissionAccruals {

    public enum Outcome { ACCRUED, NO_AGENT, NO_POLICY, EXCLUDED, ZERO }

    private static final Logger log = LoggerFactory.getLogger(CommissionAccruals.class);

    private record Candidate(long id, String sourceType, String sourceRef, String sourceLineRef, long skuBindingId, int qty, LocalDateTime createdAt,
            LocalDateTime updatedAt, LocalDateTime deliveredAt) {
    }

    private record Snapshot(long paidFen, Long costCnyFen) {
    }

    private static final String CANDIDATES = """
            SELECT l.id, l.source_type, l.source_ref, l.source_line_ref, l.sku_binding_id, l.qty, l.created_at, l.updated_at,
                   (SELECT MIN(cr.delivered_at) FROM lr_card_asset c JOIN lr_card_credential cr ON cr.card_asset_id = c.id
                     WHERE c.fulfillment_line_id = l.id) AS delivered_at
              FROM lr_fulfillment_line l
              LEFT JOIN lr_commission_accrual a ON a.source_type = l.source_type AND a.source_line_ref = l.source_line_ref
             WHERE a.id IS NULL
               AND (l.status = 'COMPLETED'
                    OR EXISTS (SELECT 1 FROM lr_card_asset c JOIN lr_card_credential cr ON cr.card_asset_id = c.id WHERE c.fulfillment_line_id = l.id))
             ORDER BY l.id
             LIMIT ?
            """;

    /** 入账前已完成的退款：整卡退款按卡所在行，加包退款按套餐所在行。 */
    private static final String REFUNDED_BEFORE = """
            SELECT COALESCE(SUM(rc.refund_fen), 0)
              FROM lr_refund_case rc
              JOIN lr_card_asset c ON c.id = rc.card_asset_id
              LEFT JOIN lr_card_plan p ON p.id = rc.card_plan_id
             WHERE rc.status = 'DONE' AND COALESCE(p.fulfillment_line_id, c.fulfillment_line_id) = ?
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final CommissionPolicies policies;
    private final Supplier<List<CommissionStrategy>> strategies;
    private final AgentDirectory agents;
    private final CommissionLedger ledger;
    private final ZoneId settleZone;
    private final Clock clock;

    public CommissionAccruals(TransactionOperations tx, JdbcClient jdbc, CommissionPolicies policies, Supplier<List<CommissionStrategy>> strategies,
            AgentDirectory agents, CommissionLedger ledger, ZoneId settleZone, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.policies = policies;
        this.strategies = strategies;
        this.agents = agents;
        this.ledger = ledger;
        this.settleZone = settleZone;
        this.clock = clock;
    }

    /** @return 本轮判定的行数（含无代理、无策略等） */
    public int runOnce(int limit) {
        List<Candidate> due = jdbc.sql(CANDIDATES).param(limit).query(Candidate.class).list();
        int done = 0;
        for (Candidate c : due) {
            try {
                Boolean decided = tx.execute(status -> accrue(c));
                if (Boolean.TRUE.equals(decided)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("佣金入账失败，下轮重试：履约行 {}", c.id(), e);
            }
        }
        return done;
    }

    private boolean accrue(Candidate c) {
        Optional<AgentChain> chain = "CHANNEL".equals(c.sourceType()) ? Optional.empty() : agents.chainOfOrder(c.sourceRef());
        if (chain.isEmpty()) {
            return mark(c, Outcome.NO_AGENT, null, 0, 0, null);
        }
        Agent direct = chain.get().direct();
        Optional<CommissionPolicy> found = policies.select(c.skuBindingId(), direct);
        if (found.isEmpty()) {
            return mark(c, Outcome.NO_POLICY, null, 0, 0, null);
        }
        CommissionPolicy policy = found.get();
        if ("CHANNEL".equals(c.sourceType()) && !policy.includeChannelOrders()) {
            return mark(c, Outcome.EXCLUDED, policy, 0, 0, null);
        }
        Snapshot snap = jdbc.sql("SELECT paid_fen, cost_cny_fen FROM lr_order_line_snapshot WHERE source_type = ? AND source_line_ref = ?")
                .params(c.sourceType(), c.sourceLineRef()).query(Snapshot.class).optional().orElse(new Snapshot(0, null));
        long refunded = jdbc.sql(REFUNDED_BEFORE).param(c.id()).query(Long.class).single();
        long base = Math.max(0, snap.paidFen() - refunded);
        Map<String, CommissionStrategy> byMode = strategies.get().stream().collect(Collectors.toMap(CommissionStrategy::mode, Function.identity()));
        CommissionStrategy strategy = byMode.get(policy.mode());
        if (strategy == null) {
            throw new IllegalStateException("佣金策略 " + policy.code() + " 的模式 " + policy.mode() + " 没有实现");
        }
        long pool = strategy.pool(new CommissionStrategy.Context(c.sourceType(), c.sourceLineRef(), c.skuBindingId(), c.qty(), base, snap.costCnyFen(),
                chain.get()), policy);
        AgentChain agentsOfLine = chain.get();
        long[] split = CommissionStrategy.split(pool, policy.directRate(), policy.inviteRate(), agentsOfLine.inviter() != null);
        Instant anchor = anchor(c, policy.settleAnchor());
        if (split[0] + split[1] == 0) {
            return mark(c, Outcome.ZERO, policy, base, pool, anchor);
        }
        if (!mark(c, Outcome.ACCRUED, policy, base, pool, anchor)) {
            return false;
        }
        Instant settleAt = settleAt(anchor, policy.settleDays());
        ledger.accrue(direct.agentRef(), 1, c.sourceType(), c.sourceLineRef(), policy, split[0], settleAt);
        if (agentsOfLine.inviter() != null) {
            ledger.accrue(agentsOfLine.inviter().agentRef(), 2, c.sourceType(), c.sourceLineRef(), policy, split[1], settleAt);
        }
        return true;
    }

    private boolean mark(Candidate c, Outcome outcome, CommissionPolicy policy, long base, long pool, Instant anchor) {
        try {
            jdbc.sql("""
                    INSERT INTO lr_commission_accrual (source_type, source_line_ref, outcome, policy_id, policy_version, base_fen, pool_fen, anchor_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(c.sourceType(), c.sourceLineRef(), outcome.name(), policy == null ? null : policy.id(),
                    policy == null ? null : policy.version(), base, pool, anchor == null ? null : UtcColumns.toColumn(anchor)).update();
            return true;
        } catch (DuplicateKeyException concurrent) {
            return false;
        }
    }

    private Instant anchor(Candidate c, SettleAnchor anchor) {
        LocalDateTime at = switch (anchor) {
            case PAID -> c.createdAt();
            case DELIVERED -> c.deliveredAt() != null ? c.deliveredAt() : c.updatedAt();
        };
        return at == null ? clock.instant() : UtcColumns.fromColumn(at);
    }

    /** T+0 = 锚点即可提现；T+N = 锚点所在自然日（结算时区）后第 N 天零点。 */
    Instant settleAt(Instant anchor, int settleDays) {
        if (settleDays <= 0) {
            return anchor;
        }
        return anchor.atZone(settleZone).toLocalDate().plusDays(settleDays).atStartOfDay(settleZone).toInstant();
    }
}
