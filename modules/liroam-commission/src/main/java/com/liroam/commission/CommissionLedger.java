package com.liroam.commission;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.commission.AgentDirectory.Agent;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.PageResult;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.refund.CommissionReversal;

/**
 * 自研佣金账本（domain-design §4.4、§9.3）：只增，冲正写反向行不改原行；余额表 {@code lr_agent_balance} 由账本重算。
 * <ul>
 *   <li>待结算 = PENDING 合计；可提现 = SETTLEABLE / SETTLED 合计（含提现负行，冲正后可为负）；已提现 = 提现行合计取反。</li>
 *   <li>冲正：原行 PENDING 则冲正行也 PENDING（同一结算时间），否则立即扣可提现。</li>
 * </ul>
 */
public class CommissionLedger implements CommissionReversal {

    public record Balance(String agentRef, long pendingFen, long settleableFen, long withdrawnFen) {
    }

    public record AgentBalance(String agentRef, String agentName, long pendingFen, long settleableFen, long withdrawnFen, long inFlightFen) {
    }

    public record EntryView(long id, String agentRef, String agentName, int level, String sourceType, String sourceLineRef, String type,
            String typeText, long amountFen, String status, String statusText, String settleableAt, String createdAt, String policyCode,
            Integer policyVersion) {
    }

    private record EntryRow(long id, String agentRef, int level, String sourceType, String sourceLineRef, String type, long amountFen, String status,
            LocalDateTime settleableAt, LocalDateTime createdAt, String policyCode, Integer policyVersion) {
    }

    private record AccrueEntry(long id, String agentRef, long amountFen, String status, LocalDateTime settleableAt, long policyId, int policyVersion,
            String sourceType) {
    }

    private static final String ENTRY_SELECT = """
            SELECT e.id, e.agent_ref, e.level, e.source_type, e.source_line_ref, e.type, e.amount_fen, e.status, e.settleable_at, e.created_at,
                   p.code AS policy_code, e.policy_version
              FROM lr_commission_entry e LEFT JOIN lr_commission_policy p ON p.id = e.policy_id
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final AgentDirectory agents;
    private final AuditLog audit;
    private final Clock clock;

    public CommissionLedger(TransactionOperations tx, JdbcClient jdbc, AgentDirectory agents, AuditLog audit, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.agents = agents;
        this.audit = audit;
        this.clock = clock;
    }

    /** 入账一行；到期时间已过（T+0）直接可提现。调用方事务内。 */
    void accrue(String agentRef, int level, String sourceType, String sourceLineRef, CommissionPolicy policy, long amountFen, Instant settleAt) {
        if (amountFen <= 0) {
            return;
        }
        Instant now = clock.instant();
        String status = settleAt.isAfter(now) ? "PENDING" : "SETTLEABLE";
        jdbc.sql("""
                INSERT INTO lr_commission_entry (agent_ref, level, source_type, source_line_ref, policy_id, policy_version, type, amount_fen, status,
                                                 settleable_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'ACCRUE', ?, ?, ?, ?, ?)
                """).params(agentRef, level, sourceType, sourceLineRef, policy.id(), policy.version(), amountFen, status,
                UtcColumns.toColumn(settleAt), UtcColumns.toColumn(now), UtcColumns.toColumn(now)).update();
        recompute(agentRef);
    }

    /** 提现打款登记后写提现负行（已结清）。调用方事务内。 */
    void withdraw(String agentRef, long withdrawalId, long amountFen) {
        LocalDateTime now = UtcColumns.toColumn(clock.instant());
        jdbc.sql("""
                INSERT INTO lr_commission_entry (agent_ref, level, source_type, source_line_ref, policy_id, policy_version, type, amount_fen, status,
                                                 settleable_at, created_at, updated_at)
                VALUES (?, 1, 'WITHDRAWAL', ?, 0, 0, 'WITHDRAW', ?, 'SETTLED', ?, ?, ?)
                """).params(agentRef, "W:" + withdrawalId, -amountFen, now, now, now).update();
        recompute(agentRef);
    }

    /** 到期的待结算行转可提现；返回转了多少行。 */
    public int settleDue() {
        LocalDateTime now = UtcColumns.toColumn(clock.instant());
        Integer moved = tx.execute(status -> {
            List<String> touched = jdbc.sql("SELECT DISTINCT agent_ref FROM lr_commission_entry WHERE status = 'PENDING' AND settleable_at <= ?")
                    .param(now).query(String.class).list();
            if (touched.isEmpty()) {
                return 0;
            }
            int n = jdbc.sql("""
                    UPDATE lr_commission_entry SET status = 'SETTLEABLE', version = version + 1, updated_at = ?
                     WHERE status = 'PENDING' AND settleable_at <= ?
                    """).params(now, now).update();
            touched.forEach(this::recompute);
            return n;
        });
        return moved == null ? 0 : moved;
    }

    /**
     * 退款完成时冲正（退款单事务内）：对该订单行每条入账行按「退款额 / 入账基数」冲正，累计退款达到基数时冲掉余数（末笔轧差）。
     * 幂等键 = (退款单, 入账行)。没有入账（未交码就退、无代理、无策略）则只记审计。
     */
    @Override
    public void reverse(long refundCaseId, String sourceLineRef, long refundFen, long paidFen) {
        Long base = jdbc.sql("SELECT base_fen FROM lr_commission_accrual WHERE source_line_ref = ? AND outcome = 'ACCRUED'").param(sourceLineRef)
                .query(Long.class).optional().orElse(null);
        if (base == null || base <= 0 || refundFen <= 0) {
            audit.record("system:commission", "COMMISSION_REVERSE_SKIPPED", "REFUND_CASE", Long.toString(refundCaseId), null,
                    "{\"sourceLineRef\":\"" + sourceLineRef + "\",\"reason\":\"该订单行没有佣金入账\"}");
            return;
        }
        List<AccrueEntry> accrued = jdbc.sql("""
                SELECT id, agent_ref, amount_fen, status, settleable_at, policy_id, policy_version, source_type
                  FROM lr_commission_entry WHERE source_line_ref = ? AND type = 'ACCRUE' ORDER BY id
                """).param(sourceLineRef).query(AccrueEntry.class).list();
        Instant now = clock.instant();
        List<String> parts = new ArrayList<>();
        for (AccrueEntry e : accrued) {
            long reversed = -jdbc.sql("SELECT COALESCE(SUM(amount_fen), 0) FROM lr_commission_entry WHERE type = 'REVERSE' AND reverses_entry_id = ?")
                    .param(e.id()).query(Long.class).single();
            long refundedBefore = jdbc.sql("""
                    SELECT COALESCE(SUM(rc.refund_fen), 0) FROM lr_refund_case rc
                     WHERE rc.id IN (SELECT refund_case_id FROM lr_commission_entry WHERE type = 'REVERSE' AND reverses_entry_id = ?)
                    """).param(e.id()).query(Long.class).single();
            long remaining = e.amountFen() - reversed;
            long amount = refundedBefore + refundFen >= base ? remaining
                    : Math.min(remaining, CommissionStrategy.fen(e.amountFen(), java.math.BigDecimal.valueOf(refundFen)
                            .divide(java.math.BigDecimal.valueOf(base), 12, java.math.RoundingMode.HALF_UP)));
            if (amount <= 0) {
                continue;
            }
            boolean pending = "PENDING".equals(e.status());
            try {
                jdbc.sql("""
                        INSERT INTO lr_commission_entry (agent_ref, level, source_type, source_line_ref, policy_id, policy_version, type, amount_fen,
                                                         status, settleable_at, reverses_entry_id, refund_case_id, created_at, updated_at)
                        SELECT agent_ref, level, source_type, source_line_ref, policy_id, policy_version, 'REVERSE', ?, ?, ?, id, ?, ?, ?
                          FROM lr_commission_entry WHERE id = ?
                        """).params(-amount, pending ? "PENDING" : "SETTLEABLE", pending ? e.settleableAt() : UtcColumns.toColumn(now),
                        refundCaseId, UtcColumns.toColumn(now), UtcColumns.toColumn(now), e.id()).update();
            } catch (DuplicateKeyException replay) {
                continue;
            }
            recompute(e.agentRef());
            parts.add("{\"entry\":" + e.id() + ",\"agent\":\"" + e.agentRef() + "\",\"fen\":" + (-amount) + "}");
        }
        audit.record("system:commission", "COMMISSION_REVERSE", "REFUND_CASE", Long.toString(refundCaseId), null,
                "{\"sourceLineRef\":\"" + sourceLineRef + "\",\"refundFen\":" + refundFen + ",\"baseFen\":" + base + ",\"rows\":[" + String.join(",", parts)
                        + "]}");
    }

    /** 从账本重算一个代理的余额投影（调用方事务内）。 */
    void recompute(String agentRef) {
        jdbc.sql("""
                INSERT INTO lr_agent_balance (agent_ref, pending_fen, settleable_fen, withdrawn_fen)
                SELECT ?, COALESCE(SUM(CASE WHEN status = 'PENDING' THEN amount_fen END), 0),
                          COALESCE(SUM(CASE WHEN status IN ('SETTLEABLE', 'SETTLED') THEN amount_fen END), 0),
                          -COALESCE(SUM(CASE WHEN type = 'WITHDRAW' THEN amount_fen END), 0)
                  FROM lr_commission_entry WHERE agent_ref = ?
                ON DUPLICATE KEY UPDATE pending_fen = VALUES(pending_fen), settleable_fen = VALUES(settleable_fen),
                                        withdrawn_fen = VALUES(withdrawn_fen), version = version + 1, updated_at = UTC_TIMESTAMP(3)
                """).params(agentRef, agentRef).update();
    }

    public Balance balance(String agentRef) {
        return jdbc.sql("SELECT agent_ref, pending_fen, settleable_fen, withdrawn_fen FROM lr_agent_balance WHERE agent_ref = ?").param(agentRef)
                .query(Balance.class).optional().orElse(new Balance(agentRef, 0, 0, 0));
    }

    /** 在途提现（已申请、已审批未打款）合计。 */
    long inFlight(String agentRef) {
        return jdbc.sql("SELECT COALESCE(SUM(amount_fen), 0) FROM lr_withdrawal WHERE agent_ref = ? AND status IN ('APPLIED', 'APPROVED')")
                .param(agentRef).query(Long.class).single();
    }

    /** 账本明细；{@code agentRef} 为空 = 全部代理。页码从 1 开始。 */
    public PageResult<EntryView> entries(String agentRef, int pageNumber, int pageSize) {
        String where = agentRef == null || agentRef.isBlank() ? "" : " WHERE e.agent_ref = ?";
        List<Object> args = new ArrayList<>();
        if (!where.isEmpty()) {
            args.add(agentRef.trim());
        }
        int size = PageResult.clampSize(pageSize);
        int current = Math.max(pageNumber, 1);
        long total = jdbc.sql("SELECT COUNT(*) FROM lr_commission_entry e" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(PageResult.offset(current, size));
        List<EntryRow> rows = jdbc.sql(ENTRY_SELECT + where + " ORDER BY e.id DESC LIMIT ? OFFSET ?").params(pageArgs).query(EntryRow.class).list();
        Map<String, Agent> names = agents.agents(rows.stream().map(EntryRow::agentRef).distinct().toList());
        List<EntryView> views = rows.stream().map(r -> new EntryView(r.id(), r.agentRef(), name(names, r.agentRef()), r.level(), r.sourceType(),
                r.sourceLineRef(), r.type(), typeText(r.type()), r.amountFen(), r.status(), statusText(r.status()), time(r.settleableAt()),
                time(r.createdAt()), r.policyCode(), r.policyVersion())).toList();
        return new PageResult<>(views, total, current, size);
    }

    public PageResult<AgentBalance> balances(int pageNumber, int pageSize) {
        int size = PageResult.clampSize(pageSize);
        int current = Math.max(pageNumber, 1);
        long total = jdbc.sql("SELECT COUNT(*) FROM lr_agent_balance").query(Long.class).single();
        List<Balance> rows = jdbc.sql("SELECT agent_ref, pending_fen, settleable_fen, withdrawn_fen FROM lr_agent_balance ORDER BY id LIMIT ? OFFSET ?")
                .params(size, PageResult.offset(current, size)).query(Balance.class).list();
        Map<String, Agent> names = agents.agents(rows.stream().map(Balance::agentRef).toList());
        return new PageResult<>(rows.stream().map(b -> new AgentBalance(b.agentRef(), name(names, b.agentRef()), b.pendingFen(), b.settleableFen(),
                b.withdrawnFen(), inFlight(b.agentRef()))).toList(), total, current, size);
    }

    static String typeText(String type) {
        return switch (type) {
            case "ACCRUE" -> "佣金入账";
            case "REVERSE" -> "退款冲正";
            case "ADJUST" -> "人工调整";
            case "WITHDRAW" -> "提现";
            default -> type;
        };
    }

    static String statusText(String status) {
        return switch (status) {
            case "PENDING" -> "待结算";
            case "SETTLEABLE" -> "可提现";
            case "SETTLED" -> "已结清";
            default -> status;
        };
    }

    static String name(Map<String, Agent> names, String agentRef) {
        Agent a = names.get(agentRef);
        return a == null ? agentRef : a.name();
    }

    private static String time(LocalDateTime t) {
        return t == null ? null : DisplayTime.format(UtcColumns.fromColumn(t));
    }
}
