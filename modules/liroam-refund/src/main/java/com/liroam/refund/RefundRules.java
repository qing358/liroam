package com.liroam.refund;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.CardActions.PoolDisposition;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.CardPlanStatus;

/**
 * 退款规则（{@code lr_refund_rule}，domain-design §9.2）：按「退款时卡/套餐状态 + 对象」取最新版本；没有规则一律 NEED_APPROVAL（不自动放行）。
 * 改规则 = 插入新版本 + 审计，旧版本留档（退款单记 {@code rule_version}）。
 */
public class RefundRules {

    /** dev/test 默认规则（§9.2 全表）的类路径；集成方 dev 种子调 {@link #seedDevDefaults}。 */
    public static final String DEV_SEED = "liroam/dev-seed/refund-rules.sql";

    public enum TargetType { CARD, PLAN }

    public enum Decision { AUTO_APPROVE, NEED_APPROVAL, DEFAULT_REJECT }

    /** 批准时对卡做什么：NONE 不动；CANCEL 取消未执行指令；SUSPEND/TERMINATE 请求上游改状态（终止按能力降级）。 */
    public enum CardAction { NONE, CANCEL, SUSPEND, TERMINATE }

    /** @param poolAction 可空（收尾时按 VOID）；@param version 0 = 没有规则时的兜底 */
    public record Rule(Long id, String cardState, TargetType targetType, Decision decision, CardAction cardAction, PoolDisposition poolAction,
            boolean reverseCommission, int version) {
    }

    /** 后台改规则的入参（JSON camelCase）；{@code version} = 基于哪个版本改，与当前最新不一致即拒绝。 */
    public record RuleInput(Long id, String cardState, String targetType, String decision, String cardAction, String poolAction,
            Boolean reverseCommission, Integer version) {
    }

    private static final String SELECT = """
            SELECT r.id, r.card_state, r.target_type, r.decision, r.card_action, r.pool_action, r.reverse_commission, r.version
              FROM lr_refund_rule r
             WHERE r.version = (SELECT MAX(x.version) FROM lr_refund_rule x WHERE x.card_state = r.card_state AND x.target_type = r.target_type)
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final AuditLog audit;

    public RefundRules(TransactionOperations tx, JdbcClient jdbc, AuditLog audit) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** 执行 {@link #DEV_SEED}（幂等）。只给 dev/test 用。 */
    public static void seedDevDefaults(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(DEV_SEED)).execute(dataSource);
    }

    public List<Rule> current() {
        return jdbc.sql(SELECT + " ORDER BY r.target_type, r.card_state").query(Rule.class).list();
    }

    public Rule decide(String state, TargetType targetType) {
        return find(state, targetType).orElseGet(() -> new Rule(null, state, targetType, Decision.NEED_APPROVAL, CardAction.NONE, null, true, 0));
    }

    public Rule save(RuleInput input, String actor) {
        TargetType type = parse(TargetType.class, input.targetType(), "对象");
        String state = required(input.cardState(), "状态");
        boolean known = type == TargetType.CARD ? Arrays.stream(CardAssetStatus.values()).anyMatch(s -> s.name().equals(state))
                : Arrays.stream(CardPlanStatus.values()).anyMatch(s -> s.name().equals(state));
        if (!known) {
            throw new UserFacingException("不认识的" + (type == TargetType.CARD ? "卡" : "套餐") + "状态：" + state);
        }
        Decision decision = parse(Decision.class, input.decision(), "审核建议");
        CardAction cardAction = parse(CardAction.class, input.cardAction(), "卡动作");
        PoolDisposition poolAction = input.poolAction() == null || input.poolAction().isBlank() ? null
                : parse(PoolDisposition.class, input.poolAction(), "池动作");
        if (type == TargetType.PLAN && (cardAction == CardAction.SUSPEND || cardAction == CardAction.TERMINATE || poolAction != null)) {
            throw new UserFacingException("加包退款只能取消指令，不能停卡或处理池卡");
        }
        boolean reverse = input.reverseCommission() == null || input.reverseCommission();
        return tx.execute(status -> {
            Optional<Rule> before = jdbc.sql(SELECT + " AND r.card_state = ? AND r.target_type = ? FOR UPDATE").params(state, type.name())
                    .query(Rule.class).optional();
            int currentVersion = before.map(Rule::version).orElse(0);
            if (input.version() != null && input.version() != currentVersion) {
                throw new UserFacingException("这条规则已被修改，请刷新后再改");
            }
            jdbc.sql("""
                    INSERT INTO lr_refund_rule (card_state, target_type, decision, card_action, pool_action, reverse_commission, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """).params(state, type.name(), decision.name(), cardAction.name(), poolAction == null ? null : poolAction.name(), reverse,
                    currentVersion + 1).update();
            Rule saved = find(state, type).orElseThrow();
            audit.record(actor, "REFUND_RULE_SET", "REFUND_RULE", type + ":" + state, before.map(RefundRules::json).orElse(null), json(saved));
            return saved;
        });
    }

    private Optional<Rule> find(String state, TargetType type) {
        return jdbc.sql(SELECT + " AND r.card_state = ? AND r.target_type = ?").params(state, type.name()).query(Rule.class).optional();
    }

    private static String json(Rule r) {
        return "{\"decision\":\"" + r.decision() + "\",\"cardAction\":\"" + r.cardAction() + "\",\"poolAction\":"
                + (r.poolAction() == null ? "null" : "\"" + r.poolAction() + "\"") + ",\"reverseCommission\":" + r.reverseCommission()
                + ",\"version\":" + r.version() + "}";
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new UserFacingException("请填写" + label);
        }
        return value.trim();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
        String v = required(value, label);
        return Arrays.stream(type.getEnumConstants()).filter(e -> e.name().equals(v)).findFirst()
                .orElseThrow(() -> new UserFacingException("不认识的" + label + "：" + v));
    }
}
