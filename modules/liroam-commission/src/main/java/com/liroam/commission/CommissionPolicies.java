package com.liroam.commission;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.commission.AgentDirectory.Agent;
import com.liroam.commission.CommissionPolicy.ScopeType;
import com.liroam.commission.CommissionPolicy.SettleAnchor;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;

/**
 * 佣金策略（domain-design §4.4、§10）：每次保存都是同一 {@code code} 的新版本（只增），底价随版本复制；改模式、比例、T+N、锚点、
 * 是否审批都只改配置。生效版本 = 该 {@code code} 已到 {@code effective_from} 的最大版本。
 */
public class CommissionPolicies {

    /**
     * @param scopeRef      SKU = {@code lr_sku_binding.id}；AGENT_GRADE = 壳分销等级；GLOBAL 为空
     * @param withdrawApproval 空 = 需要审批
     * @param effectiveFrom 空 = 立即生效
     */
    public record PolicyInput(String code, String mode, String scopeType, String scopeRef, BigDecimal poolRate, BigDecimal directRate,
            BigDecimal inviteRate, Boolean includeChannelOrders, Integer settleDays, String settleAnchor, Boolean withdrawApproval, Instant effectiveFrom) {
    }

    /** 底价只按代理等级或单个代理之一设；{@code floorPriceFen} 为空 = 删除这条底价。 */
    public record FloorPriceInput(String agentGrade, String agentRef, long skuBindingId, Long floorPriceFen) {
    }

    public record FloorPrice(String agentGrade, String agentRef, long skuBindingId, long floorPriceFen) {
    }

    public record ModeOption(String mode, String title) {
    }

    static final int MAX_SETTLE_DAYS = 30;

    private static final String SELECT = """
            SELECT p.id, p.code, p.mode, p.scope_type, p.scope_ref,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(p.params_json, '$.poolRate')) AS DECIMAL(18,6)) AS pool_rate,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(p.params_json, '$.directRate')) AS DECIMAL(18,6)) AS direct_rate,
                   CAST(JSON_UNQUOTE(JSON_EXTRACT(p.params_json, '$.inviteRate')) AS DECIMAL(18,6)) AS invite_rate,
                   p.include_channel_orders, p.settle_days, p.settle_anchor, p.withdraw_approval, p.version, p.effective_from
              FROM lr_commission_policy p
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final Supplier<List<CommissionStrategy>> strategies;
    private final AuditLog audit;
    private final Clock clock;

    public CommissionPolicies(TransactionOperations tx, JdbcClient jdbc, Supplier<List<CommissionStrategy>> strategies, AuditLog audit, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.strategies = strategies;
        this.audit = audit;
        this.clock = clock;
    }

    public List<String> modes() {
        return modeOptions().stream().map(ModeOption::mode).toList();
    }

    public List<ModeOption> modeOptions() {
        return strategies.get().stream().map(s -> new ModeOption(s.mode(), s.title())).toList();
    }

    /** 每个策略的最新版本（含尚未生效的），后台列表用。 */
    public List<CommissionPolicy> current() {
        return jdbc.sql(SELECT + " WHERE p.version = (SELECT MAX(q.version) FROM lr_commission_policy q WHERE q.code = p.code) ORDER BY p.code")
                .query(CommissionPolicies::map).list();
    }

    public Optional<CommissionPolicy> latest(String code) {
        return jdbc.sql(SELECT + " WHERE p.code = ? ORDER BY p.version DESC LIMIT 1").param(code).query(CommissionPolicies::map).optional();
    }

    /** 当前生效的各策略版本。 */
    public List<CommissionPolicy> active() {
        LocalDateTime now = UtcColumns.toColumn(clock.instant());
        return jdbc.sql(SELECT + """
                 WHERE p.version = (SELECT MAX(q.version) FROM lr_commission_policy q WHERE q.code = p.code AND q.effective_from <= ?)
                """).param(now).query(CommissionPolicies::map).list();
    }

    /** 选一个订单行适用的策略：SKU > 一级代理等级 > 全局；同级多条取最近生效的。 */
    public Optional<CommissionPolicy> select(long skuBindingId, Agent direct) {
        String sku = Long.toString(skuBindingId);
        return active().stream()
                .filter(p -> switch (p.scopeType()) {
                    case SKU -> sku.equals(p.scopeRef());
                    case AGENT_GRADE -> direct != null && Objects.equals(direct.grade(), p.scopeRef());
                    case GLOBAL -> true;
                })
                .min(Comparator.comparing(CommissionPolicy::scopeType).thenComparing(CommissionPolicy::effectiveFrom, Comparator.reverseOrder())
                        .thenComparing(CommissionPolicy::id, Comparator.reverseOrder()));
    }

    /** 提现是否需审批：取生效的全局策略，没有全局策略时需要审批。 */
    public boolean withdrawApprovalRequired() {
        return active().stream().filter(p -> p.scopeType() == ScopeType.GLOBAL).max(Comparator.comparing(CommissionPolicy::effectiveFrom))
                .map(CommissionPolicy::withdrawApproval).orElse(true);
    }

    public CommissionPolicy save(PolicyInput in, String actor) {
        PolicyInput v = validate(in);
        return tx.execute(status -> {
            Optional<CommissionPolicy> previous = latest(v.code());
            CommissionPolicy saved = insert(v, previous.map(CommissionPolicy::version).orElse(0) + 1);
            previous.ifPresent(p -> copyFloorPrices(p.id(), saved.id(), null));
            audit.record(actor, "COMMISSION_POLICY_SAVE", "COMMISSION_POLICY", saved.code() + "@" + saved.version(),
                    previous.map(CommissionPolicies::json).orElse(null), json(saved));
            return saved;
        });
    }

    /** 改底价也出新版本：复制上一版本全部底价，替换（或删除）这一条。 */
    public CommissionPolicy saveFloorPrice(String code, FloorPriceInput in, String actor) {
        if ((blank(in.agentGrade())) == (blank(in.agentRef()))) {
            throw new UserFacingException("底价按代理等级或单个代理二选一填写");
        }
        if (in.floorPriceFen() != null && in.floorPriceFen() < 0) {
            throw new UserFacingException("底价不能为负");
        }
        return tx.execute(status -> {
            CommissionPolicy prev = latest(code).orElseThrow(() -> new UserFacingException("找不到佣金策略 " + code));
            PolicyInput same = new PolicyInput(prev.code(), prev.mode(), prev.scopeType().name(), prev.scopeRef(), prev.poolRate(), prev.directRate(),
                    prev.inviteRate(), prev.includeChannelOrders(), prev.settleDays(), prev.settleAnchor().name(), prev.withdrawApproval(), null);
            CommissionPolicy saved = insert(same, prev.version() + 1);
            copyFloorPrices(prev.id(), saved.id(), in);
            if (in.floorPriceFen() != null) {
                jdbc.sql("INSERT INTO lr_agent_floor_price (policy_id, agent_grade, agent_ref, sku_binding_id, floor_price_fen) VALUES (?, ?, ?, ?, ?)")
                        .params(saved.id(), blank(in.agentGrade()) ? null : in.agentGrade().trim(), blank(in.agentRef()) ? null : in.agentRef().trim(),
                                in.skuBindingId(), in.floorPriceFen())
                        .update();
            }
            audit.record(actor, "COMMISSION_FLOOR_PRICE_SAVE", "COMMISSION_POLICY", saved.code() + "@" + saved.version(), null,
                    "{\"agentGrade\":" + quote(in.agentGrade()) + ",\"agentRef\":" + quote(in.agentRef()) + ",\"skuBindingId\":" + in.skuBindingId()
                            + ",\"floorPriceFen\":" + in.floorPriceFen() + "}");
            return saved;
        });
    }

    public List<FloorPrice> floorPrices(long policyId) {
        return jdbc.sql("SELECT agent_grade, agent_ref, sku_binding_id, floor_price_fen FROM lr_agent_floor_price WHERE policy_id = ? ORDER BY id")
                .param(policyId).query(FloorPrice.class).list();
    }

    /** 单个代理的底价优先于等级底价。 */
    Optional<Long> floorPriceFen(long policyId, Agent direct, long skuBindingId) {
        return jdbc.sql("""
                SELECT floor_price_fen FROM lr_agent_floor_price
                 WHERE policy_id = :policy AND sku_binding_id = :sku AND (agent_ref = :agent OR agent_grade = :grade)
                 ORDER BY agent_ref IS NULL, id DESC LIMIT 1
                """).param("policy", policyId).param("sku", skuBindingId).param("agent", direct.agentRef()).param("grade", direct.grade())
                .query(Long.class).optional();
    }

    private CommissionPolicy insert(PolicyInput v, int version) {
        Map<String, BigDecimal> params = new LinkedHashMap<>();
        params.put("poolRate", v.poolRate());
        params.put("directRate", v.directRate());
        params.put("inviteRate", v.inviteRate());
        StringBuilder json = new StringBuilder("{");
        params.forEach((k, val) -> {
            if (val != null) {
                json.append(json.length() > 1 ? "," : "").append('"').append(k).append("\":\"").append(val.toPlainString()).append('"');
            }
        });
        json.append('}');
        Instant effective = v.effectiveFrom() == null ? clock.instant() : v.effectiveFrom();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_commission_policy (code, mode, scope_type, scope_ref, params_json, include_channel_orders, settle_days, settle_anchor,
                                                  withdraw_approval, version, effective_from)
                VALUES (?, ?, ?, ?, CAST(? AS JSON), ?, ?, ?, ?, ?, ?)
                """).params(v.code(), v.mode(), v.scopeType(), v.scopeRef(), json.toString(), v.includeChannelOrders(), v.settleDays(), v.settleAnchor(),
                v.withdrawApproval(), version, UtcColumns.toColumn(effective)).update(keys);
        long id = keys.getKey().longValue();
        return jdbc.sql(SELECT + " WHERE p.id = ?").param(id).query(CommissionPolicies::map).single();
    }

    private void copyFloorPrices(long fromPolicyId, long toPolicyId, FloorPriceInput replaced) {
        for (FloorPrice fp : floorPrices(fromPolicyId)) {
            if (replaced != null && fp.skuBindingId() == replaced.skuBindingId() && Objects.equals(fp.agentGrade(), trimOrNull(replaced.agentGrade()))
                    && Objects.equals(fp.agentRef(), trimOrNull(replaced.agentRef()))) {
                continue;
            }
            jdbc.sql("INSERT INTO lr_agent_floor_price (policy_id, agent_grade, agent_ref, sku_binding_id, floor_price_fen) VALUES (?, ?, ?, ?, ?)")
                    .params(toPolicyId, fp.agentGrade(), fp.agentRef(), fp.skuBindingId(), fp.floorPriceFen()).update();
        }
    }

    private PolicyInput validate(PolicyInput in) {
        if (in == null || blank(in.code()) || !in.code().trim().matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new UserFacingException("策略编码用大写字母开头，只含大写字母、数字与下划线（64 位以内）");
        }
        if (blank(in.mode()) || !modes().contains(in.mode().trim())) {
            throw new UserFacingException("不认识的佣金模式：" + in.mode() + "（可选 " + String.join(" / ", modes()) + "）");
        }
        ScopeType scope = parse(ScopeType.class, in.scopeType(), "作用域");
        String scopeRef = trimOrNull(in.scopeRef());
        if (scope == ScopeType.GLOBAL) {
            scopeRef = null;
        } else if (scopeRef == null) {
            throw new UserFacingException(scope == ScopeType.SKU ? "按 SKU 的策略要选 SKU" : "按代理等级的策略要填等级");
        }
        String mode = in.mode().trim();
        BigDecimal pool = "PAID_RATE".equals(mode) ? rate(in.poolRate(), "抽成比例") : null;
        BigDecimal direct = rate(in.directRate() == null ? BigDecimal.ZERO : in.directRate(), "一级比例");
        BigDecimal invite = rate(in.inviteRate() == null ? BigDecimal.ZERO : in.inviteRate(), "二级比例");
        if (direct.add(invite).compareTo(BigDecimal.ONE) > 0) {
            throw new UserFacingException("一级与二级比例合计不能超过 100%");
        }
        if (in.settleDays() == null || in.settleDays() < 0 || in.settleDays() > MAX_SETTLE_DAYS) {
            throw new UserFacingException("结算窗口 T+N 的 N 取 0～" + MAX_SETTLE_DAYS);
        }
        SettleAnchor anchor = parse(SettleAnchor.class, in.settleAnchor(), "结算锚点");
        return new PolicyInput(in.code().trim(), mode, scope.name(), scopeRef, pool, direct, invite, Boolean.TRUE.equals(in.includeChannelOrders()),
                in.settleDays(), anchor.name(), !Boolean.FALSE.equals(in.withdrawApproval()), in.effectiveFrom());
    }

    private static BigDecimal rate(BigDecimal r, String label) {
        if (r == null || r.signum() < 0 || r.compareTo(BigDecimal.ONE) > 0) {
            throw new UserFacingException(label + "取 0～1（如 0.1 表示 10%）");
        }
        return r;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String label) {
        return Arrays.stream(type.getEnumConstants()).filter(e -> e.name().equals(raw == null ? null : raw.trim())).findFirst()
                .orElseThrow(() -> new UserFacingException("不认识的" + label + "：" + raw));
    }

    private static CommissionPolicy map(ResultSet rs, int n) throws SQLException {
        return new CommissionPolicy(rs.getLong("id"), rs.getString("code"), rs.getString("mode"), ScopeType.valueOf(rs.getString("scope_type")),
                rs.getString("scope_ref"), rs.getBigDecimal("pool_rate"), rs.getBigDecimal("direct_rate"), rs.getBigDecimal("invite_rate"),
                rs.getBoolean("include_channel_orders"), rs.getInt("settle_days"), SettleAnchor.valueOf(rs.getString("settle_anchor")),
                rs.getBoolean("withdraw_approval"), rs.getInt("version"), UtcColumns.fromColumn(rs.getObject("effective_from", LocalDateTime.class)));
    }

    private static String json(CommissionPolicy p) {
        List<String> parts = new ArrayList<>();
        parts.add("\"mode\":\"" + p.mode() + "\"");
        parts.add("\"scope\":\"" + p.scopeType() + (p.scopeRef() == null ? "" : ":" + p.scopeRef()) + "\"");
        parts.add("\"poolRate\":" + (p.poolRate() == null ? "null" : p.poolRate().toPlainString()));
        parts.add("\"directRate\":" + p.directRate().toPlainString());
        parts.add("\"inviteRate\":" + p.inviteRate().toPlainString());
        parts.add("\"settle\":\"T+" + p.settleDays() + "@" + p.settleAnchor() + "\"");
        parts.add("\"withdrawApproval\":" + p.withdrawApproval());
        parts.add("\"includeChannelOrders\":" + p.includeChannelOrders());
        return "{" + String.join(",", parts) + "}";
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "'") + "\"";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
