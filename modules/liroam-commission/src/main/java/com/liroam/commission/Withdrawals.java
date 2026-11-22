package com.liroam.commission;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.commission.AgentDirectory.Agent;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;

/**
 * 代理提现（domain-design §10）。无出金通道：代理申请 → 运营审批（策略可关）→ 线下打款后登记凭证，登记时写账本提现负行。
 * 申请到打款之间的金额是「在途」，占用可提现；驳回即释放。收款信息加密存，列表只显示掩码，查看明文记审计。
 */
public class Withdrawals {

    public enum Method {
        ALIPAY("支付宝"), WECHAT("微信"), BANK("银行卡");

        final String text;

        Method(String text) {
            this.text = text;
        }
    }

    public record Payee(String method, String name, String account) {
    }

    public record View(long id, String agentRef, String agentName, long amountFen, String status, String statusText, String payeeMasked,
            String voucher, String note, String createdAt, String approvedAt, String paidAt) {
    }

    /** 代理端「我的佣金」头部；非代理只有 {@code agent = false}。 */
    public record Summary(boolean agent, String agentName, long pendingFen, long settleableFen, long withdrawnFen, long inFlightFen,
            long availableFen, long minWithdrawFen, boolean approvalRequired) {
    }

    private record Row(long id, String agentRef, long amountFen, String status, String payeeMasked, String payoutVoucher, String decisionNote,
            LocalDateTime createdAt, LocalDateTime approvedAt, LocalDateTime paidAt) {
    }

    private static final String SELECT = """
            SELECT id, agent_ref, amount_fen, status, payee_masked, payout_voucher, decision_note, created_at, approved_at, paid_at FROM lr_withdrawal
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final AgentDirectory agents;
    private final CommissionLedger ledger;
    private final CommissionPolicies policies;
    private final SensitiveCipher cipher;
    private final Settings settings;
    private final AuditLog audit;
    private final Clock clock;

    public Withdrawals(TransactionOperations tx, JdbcClient jdbc, AgentDirectory agents, CommissionLedger ledger, CommissionPolicies policies,
            SensitiveCipher cipher, Settings settings, AuditLog audit, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.agents = agents;
        this.ledger = ledger;
        this.policies = policies;
        this.cipher = cipher;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    public Summary summary(String memberId) {
        Optional<Agent> agent = agents.agentOfMember(memberId);
        long min = settings.get(CommissionSettings.WITHDRAW_MIN_FEN);
        boolean approval = policies.withdrawApprovalRequired();
        if (agent.isEmpty()) {
            return new Summary(false, null, 0, 0, 0, 0, 0, min, approval);
        }
        CommissionLedger.Balance b = ledger.balance(agent.get().agentRef());
        long inFlight = ledger.inFlight(agent.get().agentRef());
        return new Summary(true, agent.get().name(), b.pendingFen(), b.settleableFen(), b.withdrawnFen(), inFlight,
                Math.max(0, b.settleableFen() - inFlight), min, approval);
    }

    public View apply(String memberId, long amountFen, Payee payee) {
        Agent agent = agents.agentOfMember(memberId).orElseThrow(() -> new UserFacingException("你还不是审核通过的代理，不能提现"));
        Payee p = validate(payee);
        long min = settings.get(CommissionSettings.WITHDRAW_MIN_FEN);
        if (amountFen < min) {
            throw new UserFacingException("单笔最低提现 " + yuan(min) + " 元");
        }
        boolean approval = policies.withdrawApprovalRequired();
        return tx.execute(status -> {
            ledger.recompute(agent.agentRef());
            long settleable = jdbc.sql("SELECT settleable_fen FROM lr_agent_balance WHERE agent_ref = ? FOR UPDATE").param(agent.agentRef())
                    .query(Long.class).single();
            long available = Math.max(0, settleable - ledger.inFlight(agent.agentRef()));
            if (amountFen > available) {
                throw new UserFacingException("超过可提现金额（当前可提现 " + yuan(available) + " 元）");
            }
            SensitiveCipher.Sealed sealed = cipher.seal(SensitiveField.PAYEE, String.join("\t", p.method(), p.name(), p.account()));
            LocalDateTime now = UtcColumns.toColumn(clock.instant());
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO lr_withdrawal (agent_ref, amount_fen, status, payout_channel, payee_cipher, payee_masked, key_version,
                                               approved_by, approved_at, created_at, updated_at)
                    VALUES (?, ?, ?, 'MANUAL', ?, ?, ?, ?, ?, ?, ?)
                    """).params(agent.agentRef(), amountFen, approval ? "APPLIED" : "APPROVED", sealed.bytes(), mask(p), sealed.keyVersion(),
                    approval ? null : "system:auto", approval ? null : now, now, now).update(keys);
            long id = keys.getKey().longValue();
            audit.record("member:" + memberId, "WITHDRAWAL_APPLY", "WITHDRAWAL", Long.toString(id), null,
                    "{\"agentRef\":\"" + agent.agentRef() + "\",\"amountFen\":" + amountFen + ",\"approval\":" + approval + "}");
            return view(id);
        });
    }

    public View approve(long id, String actor) {
        return decide(id, actor, "APPLIED", "APPROVED", "WITHDRAWAL_APPROVE", null, null);
    }

    public View reject(long id, String actor, String note) {
        if (note == null || note.isBlank()) {
            throw new UserFacingException("驳回要写原因");
        }
        return decide(id, actor, null, "REJECTED", "WITHDRAWAL_REJECT", note.trim(), null);
    }

    /** 线下打款后登记；写账本提现负行。 */
    public View markPaid(long id, String actor, String voucher, String note) {
        if (voucher == null || voucher.isBlank()) {
            throw new UserFacingException("登记打款要填凭证号或回单说明");
        }
        return decide(id, actor, "APPROVED", "PAID", "WITHDRAWAL_PAID", note == null || note.isBlank() ? null : note.trim(), voucher.trim());
    }

    /** 查看收款明文（运营打款用），记审计。 */
    public Payee payee(long id, String actor) {
        record Sealed(byte[] payeeCipher, Integer keyVersion) {
        }
        Sealed s = jdbc.sql("SELECT payee_cipher, key_version FROM lr_withdrawal WHERE id = ?").param(id).query(Sealed.class).optional()
                .orElseThrow(() -> new UserFacingException("提现申请不存在"));
        if (s.payeeCipher() == null) {
            throw new UserFacingException("这笔提现没有收款信息");
        }
        String[] parts = cipher.open(SensitiveField.PAYEE, new SensitiveCipher.Sealed(s.keyVersion(), s.payeeCipher())).split("\t", -1);
        audit.record(actor, "WITHDRAWAL_PAYEE_VIEW", "WITHDRAWAL", Long.toString(id), null, null);
        return new Payee(parts[0], parts[1], parts[2]);
    }

    public List<View> mine(String memberId) {
        Optional<Agent> agent = agents.agentOfMember(memberId);
        if (agent.isEmpty()) {
            return List.of();
        }
        List<Row> rows = jdbc.sql(SELECT + " WHERE agent_ref = ? ORDER BY id DESC LIMIT 50").param(agent.get().agentRef()).query(Row.class).list();
        return rows.stream().map(r -> view(r, Map.of(agent.get().agentRef(), agent.get()))).toList();
    }

    public PageResult<View> page(String status, int pageNumber, int pageSize) {
        String where = status == null || status.isBlank() ? "" : " WHERE status = ?";
        List<Object> args = new ArrayList<>();
        if (!where.isEmpty()) {
            args.add(status.trim());
        }
        int size = PageResult.clampSize(pageSize);
        int current = Math.max(pageNumber, 1);
        long total = jdbc.sql("SELECT COUNT(*) FROM lr_withdrawal" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(PageResult.offset(current, size));
        List<Row> rows = jdbc.sql(SELECT + where + " ORDER BY id DESC LIMIT ? OFFSET ?").params(pageArgs).query(Row.class).list();
        Map<String, Agent> names = agents.agents(rows.stream().map(Row::agentRef).distinct().toList());
        return new PageResult<>(rows.stream().map(r -> view(r, names)).toList(), total, current, size);
    }

    private View decide(long id, String actor, String requiredFrom, String to, String action, String note, String voucher) {
        return tx.execute(status -> {
            Row row = jdbc.sql(SELECT + " WHERE id = ? FOR UPDATE").param(id).query(Row.class).optional()
                    .orElseThrow(() -> new UserFacingException("提现申请不存在"));
            boolean allowed = requiredFrom != null ? requiredFrom.equals(row.status()) : List.of("APPLIED", "APPROVED").contains(row.status());
            if (!allowed) {
                throw new UserFacingException("这笔提现当前是「" + statusText(row.status()) + "」，不能" + actionText(to));
            }
            LocalDateTime now = UtcColumns.toColumn(clock.instant());
            switch (to) {
                case "APPROVED" -> jdbc.sql("UPDATE lr_withdrawal SET status = 'APPROVED', approved_by = ?, approved_at = ?, version = version + 1, updated_at = ? WHERE id = ?")
                        .params(actor, now, now, id).update();
                case "REJECTED" -> jdbc.sql("UPDATE lr_withdrawal SET status = 'REJECTED', approved_by = ?, approved_at = ?, decision_note = ?, version = version + 1, updated_at = ? WHERE id = ?")
                        .params(actor, now, note, now, id).update();
                case "PAID" -> {
                    jdbc.sql("UPDATE lr_withdrawal SET status = 'PAID', paid_by = ?, paid_at = ?, payout_voucher = ?, decision_note = COALESCE(?, decision_note), version = version + 1, updated_at = ? WHERE id = ?")
                            .params(actor, now, voucher, note, now, id).update();
                    ledger.withdraw(row.agentRef(), id, row.amountFen());
                }
                default -> throw new IllegalArgumentException(to);
            }
            audit.record(actor, action, "WITHDRAWAL", Long.toString(id), "{\"status\":\"" + row.status() + "\"}",
                    "{\"status\":\"" + to + "\",\"amountFen\":" + row.amountFen() + "}");
            return view(id);
        });
    }

    private View view(long id) {
        Row r = jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Row.class).single();
        return view(r, agents.agents(List.of(r.agentRef())));
    }

    private static View view(Row r, Map<String, Agent> names) {
        return new View(r.id(), r.agentRef(), CommissionLedger.name(names, r.agentRef()), r.amountFen(), r.status(), statusText(r.status()),
                r.payeeMasked(), r.payoutVoucher(), r.decisionNote(), time(r.createdAt()), time(r.approvedAt()), time(r.paidAt()));
    }

    private static Payee validate(Payee p) {
        if (p == null) {
            throw new UserFacingException("请填写收款信息");
        }
        Method method;
        try {
            method = Method.valueOf(p.method() == null ? "" : p.method().trim());
        } catch (IllegalArgumentException e) {
            throw new UserFacingException("收款方式只支持支付宝、微信、银行卡");
        }
        String name = p.name() == null ? "" : p.name().trim();
        String account = p.account() == null ? "" : p.account().replaceAll("\\s", "");
        if (name.isEmpty() || name.length() > 32 || name.contains("\t")) {
            throw new UserFacingException("请填写收款人姓名（32 字以内）");
        }
        if (account.length() < 4 || account.length() > 64) {
            throw new UserFacingException("请填写收款账号");
        }
        return new Payee(method.name(), name, account);
    }

    static String mask(Payee p) {
        String name = p.name().substring(0, 1) + "*".repeat(Math.max(1, p.name().length() - 1));
        String a = p.account();
        String account = a.length() >= 8 ? a.substring(0, 3) + "****" + a.substring(a.length() - 4) : "****" + a.substring(a.length() - 4);
        return Method.valueOf(p.method()).text + " " + name + " " + account;
    }

    static String statusText(String status) {
        return switch (status) {
            case "APPLIED" -> "待审核";
            case "APPROVED" -> "待打款";
            case "REJECTED" -> "已驳回";
            case "PAID" -> "已打款";
            default -> status;
        };
    }

    private static String actionText(String to) {
        return switch (to) {
            case "APPROVED" -> "审批";
            case "REJECTED" -> "驳回";
            case "PAID" -> "登记打款";
            default -> to;
        };
    }

    private static String yuan(long fen) {
        return java.math.BigDecimal.valueOf(fen, 2).toPlainString();
    }

    private static String time(LocalDateTime t) {
        return t == null ? null : DisplayTime.format(UtcColumns.fromColumn(t));
    }
}
