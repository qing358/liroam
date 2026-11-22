package com.liroam.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.CardActions.PoolDisposition;
import com.liroam.refund.RefundRules.CardAction;
import com.liroam.refund.RefundRules.Decision;
import com.liroam.refund.RefundRules.Rule;
import com.liroam.refund.RefundRules.RuleInput;
import com.liroam.refund.RefundRules.TargetType;

/** dev 默认规则（§9.2 全表、可重复执行）、取最新版本、改即新版本 + 审计、乐观校验、入参校验；默认冲正只记审计。 */
class RefundRulesTest {

    private static RefundFixture f;
    private static RefundRules rules;

    @BeforeAll
    static void start() {
        f = RefundFixture.start("refund_rules");
        rules = f.rules;
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    @Test
    void devSeedIsTheWholeSection9_2TableAndIdempotent() {
        RefundRules.seedDevDefaults(f.h.bean(DataSource.class));

        assertThat(f.h.count("lr_refund_rule")).isEqualTo(14);
        assertThat(rules.current()).extracting(Rule::targetType, Rule::cardState, Rule::decision, Rule::cardAction, Rule::poolAction,
                Rule::reverseCommission, Rule::version).containsExactly(
                tuple(TargetType.CARD, "ALLOCATED", Decision.AUTO_APPROVE, CardAction.CANCEL, PoolDisposition.RETURN, true, 1),
                tuple(TargetType.CARD, "CREATED", Decision.AUTO_APPROVE, CardAction.CANCEL, PoolDisposition.RETURN, true, 1),
                tuple(TargetType.CARD, "DELIVERED", Decision.NEED_APPROVAL, CardAction.TERMINATE, PoolDisposition.VOID, true, 1),
                tuple(TargetType.CARD, "EXPIRED", Decision.DEFAULT_REJECT, CardAction.NONE, null, false, 1),
                tuple(TargetType.CARD, "IN_USE", Decision.DEFAULT_REJECT, CardAction.TERMINATE, null, true, 1),
                tuple(TargetType.CARD, "PROVISIONED", Decision.AUTO_APPROVE, CardAction.TERMINATE, PoolDisposition.VOID, true, 1),
                tuple(TargetType.CARD, "SUSPENDED", Decision.DEFAULT_REJECT, CardAction.TERMINATE, null, true, 1),
                tuple(TargetType.CARD, "TERMINATED", Decision.DEFAULT_REJECT, CardAction.NONE, null, false, 1),
                tuple(TargetType.PLAN, "DEPLETED", Decision.DEFAULT_REJECT, CardAction.NONE, null, false, 1),
                tuple(TargetType.PLAN, "EXPIRED", Decision.DEFAULT_REJECT, CardAction.NONE, null, false, 1),
                tuple(TargetType.PLAN, "IN_USE", Decision.DEFAULT_REJECT, CardAction.NONE, null, false, 1),
                tuple(TargetType.PLAN, "PENDING", Decision.AUTO_APPROVE, CardAction.CANCEL, null, true, 1),
                tuple(TargetType.PLAN, "PROVISIONING", Decision.AUTO_APPROVE, CardAction.CANCEL, null, true, 1),
                tuple(TargetType.PLAN, "READY", Decision.NEED_APPROVAL, CardAction.CANCEL, null, true, 1));
    }

    @Test
    void saveAddsNewVersionWithAuditAndDecideUsesLatest() {
        Rule saved = rules.save(new RuleInput(null, "DELIVERED", "CARD", "AUTO_APPROVE", "TERMINATE", "VOID", true, 1), "manager:admin");

        assertThat(saved).extracting(Rule::decision, Rule::version).containsExactly(Decision.AUTO_APPROVE, 2);
        assertThat(rules.decide("DELIVERED", TargetType.CARD).version()).isEqualTo(2);
        assertThat(f.h.count("lr_refund_rule WHERE card_state = 'DELIVERED' AND target_type = 'CARD'")).as("旧版本留档").isEqualTo(2);
        assertThat(rules.current()).hasSize(14);
        assertThat(f.h.jdbc.queryForMap("SELECT actor, target_id, JSON_UNQUOTE(JSON_EXTRACT(before_json, '$.decision')) AS before_decision, "
                + "JSON_UNQUOTE(JSON_EXTRACT(after_json, '$.decision')) AS after_decision FROM lr_audit_log WHERE action = 'REFUND_RULE_SET'"))
                .containsEntry("actor", "manager:admin").containsEntry("target_id", "CARD:DELIVERED")
                .containsEntry("before_decision", "NEED_APPROVAL").containsEntry("after_decision", "AUTO_APPROVE");

        assertThatThrownBy(() -> rules.save(new RuleInput(null, "DELIVERED", "CARD", "NEED_APPROVAL", "TERMINATE", "VOID", true, 1), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("这条规则已被修改，请刷新后再改");

        Rule added = rules.save(new RuleInput(null, "EXCEPTION", "CARD", "NEED_APPROVAL", "NONE", null, null, null), "op");
        assertThat(added).extracting(Rule::version, Rule::reverseCommission).containsExactly(1, true);
    }

    @Test
    void decideFallsBackToNeedApprovalWithoutRule() {
        assertThat(rules.decide("EXCEPTION", TargetType.CARD)).extracting(Rule::decision, Rule::cardAction, Rule::poolAction, Rule::version)
                .containsExactly(Decision.NEED_APPROVAL, CardAction.NONE, null, 0);
        assertThat(rules.decide("FAILED", TargetType.PLAN).decision()).isEqualTo(Decision.NEED_APPROVAL);
    }

    @Test
    void invalidInputIsRefusedInChinese() {
        assertThatThrownBy(() -> rules.save(new RuleInput(null, "DELIVERED", "ORDER", "AUTO_APPROVE", "NONE", null, true, null), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("不认识的对象：ORDER");
        assertThatThrownBy(() -> rules.save(new RuleInput(null, "READY", "CARD", "AUTO_APPROVE", "NONE", null, true, null), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("不认识的卡状态：READY");
        assertThatThrownBy(() -> rules.save(new RuleInput(null, "PROVISIONED", "PLAN", "AUTO_APPROVE", "NONE", null, true, null), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("不认识的套餐状态：PROVISIONED");
        assertThatThrownBy(() -> rules.save(new RuleInput(null, "DELIVERED", "CARD", "YES", "NONE", null, true, null), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("不认识的审核建议：YES");
        assertThatThrownBy(() -> rules.save(new RuleInput(null, "DELIVERED", "CARD", "AUTO_APPROVE", null, null, true, null), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("请填写卡动作");
        assertThatThrownBy(() -> rules.save(new RuleInput(null, "DELIVERED", "CARD", "AUTO_APPROVE", "NONE", "KEEP", true, null), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("不认识的池动作：KEEP");
        assertThatThrownBy(() -> rules.save(new RuleInput(null, "READY", "PLAN", "AUTO_APPROVE", "TERMINATE", null, true, null), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("加包退款只能取消指令，不能停卡或处理池卡");
        assertThat(f.h.count("lr_refund_rule")).isEqualTo(14);
    }

    @Test
    void auditOnlyReversalRecordsDeferredEntry() {
        new AuditOnlyCommissionReversal(f.h.bean(AuditLog.class)).reverse(42, "O-1-1", 1500, 3000);

        assertThat(f.h.jdbc.queryForMap("SELECT actor, target_type, target_id, CAST(after_json AS CHAR) AS after_json FROM lr_audit_log "
                + "WHERE action = 'COMMISSION_REVERSAL_DEFERRED'")).containsEntry("target_type", "REFUND_CASE").containsEntry("target_id", "42")
                .containsEntry("after_json", "{\"paidFen\": 3000, \"refundFen\": 1500, \"sourceLineRef\": \"O-1-1\"}");
    }
}
