package com.liroam.refund;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.common.state.StateMachine;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.card.CardActions.PoolDisposition;
import com.liroam.fulfillment.card.CardActions.RefundEnd;
import com.liroam.fulfillment.card.CardActions.StatusRequest;
import com.liroam.fulfillment.card.CardViewProjector;
import com.liroam.fulfillment.poll.CardRefresh;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.CardPlanStatus;
import com.liroam.refund.RefundRules.CardAction;
import com.liroam.refund.RefundRules.Decision;
import com.liroam.refund.RefundRules.Rule;
import com.liroam.refund.RefundRules.TargetType;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;

/**
 * 退款单（domain-design §9.1，P2 从「我的卡」直接发起，不经壳售后）：
 * 申请 = 按需刷新 → 按规则给建议 → 卡冻结 → HELD；批准 = 按规则做卡动作 → EXECUTING；驳回 = 解冻 → RELEASED；
 * 完成（钱已退）= 卡/套餐收尾 → 履约行推进 → 佣金冲正 → DONE。卡写入一律经 {@link CardActions}。
 */
public class RefundCases {

    public enum Status {
        HELD, APPROVED, EXECUTING, DONE, RELEASED;

        public static final StateMachine<Status> MACHINE = StateMachine.of(Status.class)
                .allow(HELD, APPROVED, RELEASED)
                .allow(APPROVED, EXECUTING)
                .allow(EXECUTING, DONE)
                .build();
    }

    public static final String AUTO_REVIEWER = "system:auto_review";

    private static final String SOURCE = "DIRECT";
    private static final Set<Status> ACTIVE = EnumSet.of(Status.HELD, Status.APPROVED, Status.EXECUTING);
    private static final Set<CardAssetStatus> BEFORE_UPSTREAM = EnumSet.of(CardAssetStatus.CREATED, CardAssetStatus.ALLOCATED,
            CardAssetStatus.EXCEPTION);

    private record CardTarget(long id, String cardNo, String ownerMemberId, CardAssetStatus status, boolean refundHold, int unitSeq,
            String sourceType, String sourceLineRef, int qty) {
    }

    private record PlanTarget(long id, long cardAssetId, String kind, CardPlanStatus status, String sourceType, String sourceLineRef) {
    }

    private record CaseRow(long id, TargetType targetType, long cardAssetId, Long cardPlanId, String cardStateAtApply, Decision decision,
            Status status, long refundFen, long paidFen, String refundBasis, String reason, String appliedBy, String decidedBy, CardAction cardAction,
            PoolDisposition poolAction, boolean reverseCommission, StatusRequest statusRequest, int version, LocalDateTime createdAt,
            String ownerMemberId, String cardNo, String sourceLineRef, String orderSn) {
    }

    private static final String CARD_TARGET = """
            SELECT c.id, c.card_no, c.owner_member_id, c.status, c.refund_hold, c.unit_seq, l.source_type, l.source_line_ref, l.qty
              FROM lr_card_asset c JOIN lr_fulfillment_line l ON l.id = c.fulfillment_line_id
             WHERE c.id = ?
            """;
    private static final String PLAN_TARGET = """
            SELECT p.id, p.card_asset_id, p.kind, p.status, l.source_type, l.source_line_ref
              FROM lr_card_plan p JOIN lr_fulfillment_line l ON l.id = p.fulfillment_line_id
             WHERE p.id = ?
            """;
    private static final String CASE_SELECT = """
            SELECT rc.id, rc.target_type, rc.card_asset_id, rc.card_plan_id, rc.card_state_at_apply, rc.decision, rc.status, rc.refund_fen,
                   rc.paid_fen, rc.refund_basis, rc.reason, rc.applied_by, rc.decided_by, rc.card_action, rc.pool_action, rc.reverse_commission,
                   rc.status_request, rc.version, rc.created_at, c.owner_member_id, c.card_no,
                   COALESCE(pl.source_line_ref, cl.source_line_ref) AS source_line_ref, COALESCE(pl.source_ref, cl.source_ref) AS order_sn
              FROM lr_refund_case rc
              JOIN lr_card_asset c ON c.id = rc.card_asset_id
              JOIN lr_fulfillment_line cl ON cl.id = c.fulfillment_line_id
              LEFT JOIN lr_card_plan p ON p.id = rc.card_plan_id
              LEFT JOIN lr_fulfillment_line pl ON pl.id = p.fulfillment_line_id
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final RefundRules rules;
    private final CardActions cards;
    private final CardRefresh refresh;
    private final CommissionReversal reversal;
    private final Settings settings;
    private final AuditLog audit;
    private final Clock clock;

    public RefundCases(TransactionOperations tx, JdbcClient jdbc, RefundRules rules, CardActions cards, CardRefresh refresh,
            CommissionReversal reversal, Settings settings, AuditLog audit, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.rules = rules;
        this.cards = cards;
        this.refresh = refresh;
        this.reversal = reversal;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 买家对自己的一张卡（或卡上的一个加包）申请退款。建议 DEFAULT_REJECT 也建单，后台可人工例外批准。
     * {@code refund.auto_review} 开且建议自动通过时直接批准。
     *
     * @param cardPlanId 空 = 整卡退款
     */
    public RefundCaseView apply(long cardAssetId, Long cardPlanId, String memberId, String reason) {
        CardTarget card = cardTarget(cardAssetId).filter(c -> c.ownerMemberId() != null && c.ownerMemberId().equals(memberId))
                .orElseThrow(() -> new UserFacingException("找不到这张卡"));
        if (cardPlanId == null && !CardViewProjector.refundOnSite(card.sourceType())) {
            throw new UserFacingException("渠道订单的卡请到下单平台申请售后");
        }
        if (card.refundHold() || hasActiveCase(card.id())) {
            throw new UserFacingException("这张卡已有退款在处理中");
        }
        if (!CardViewProjector.canApplyRefund(card.status(), false)) {
            throw new UserFacingException("这张卡" + CardViewProjector.statusText(card.status()) + "，不能再申请退款");
        }
        PlanTarget plan = cardPlanId == null ? null : planTarget(cardPlanId, card.id());
        refresh.requestOnDemand(card.id());

        TargetType type = plan == null ? TargetType.CARD : TargetType.PLAN;
        String state = plan == null ? card.status().name() : plan.status().name();
        Rule rule = rules.decide(state, type);
        String sourceType = plan == null ? card.sourceType() : plan.sourceType();
        String sourceLineRef = plan == null ? card.sourceLineRef() : plan.sourceLineRef();
        Optional<Long> paid = jdbc.sql("SELECT paid_fen FROM lr_order_line_snapshot WHERE source_type = ? AND source_line_ref = ?")
                .params(sourceType, sourceLineRef).query(Long.class).optional();
        long paidFen = paid.orElse(0L);
        long refundFen = plan == null ? unitShare(paidFen, card.qty(), card.unitSeq()) : paidFen;
        String applicant = "member:" + memberId;
        String note = reason == null || reason.isBlank() ? null : reason.trim().substring(0, Math.min(reason.trim().length(), 255));

        long id = tx.execute(status -> {
            if (!cards.hold(card.id(), applicant)) {
                throw new UserFacingException("这张卡已有退款在处理中");
            }
            String targetKey = type + ":" + (plan == null ? card.id() : plan.id());
            int seq = jdbc.sql("SELECT COUNT(*) FROM lr_refund_case WHERE source = ? AND source_ref LIKE ?").params(SOURCE, targetKey + ":%")
                    .query(Integer.class).single() + 1;
            LocalDateTime now = UtcColumns.toColumn(clock.instant());
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO lr_refund_case (source, source_ref, target_type, card_asset_id, card_plan_id, card_state_at_apply, decision,
                                                rule_version, status, refund_fen, paid_fen, refund_basis, reason, applied_by, card_action,
                                                pool_action, reverse_commission, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(SOURCE, targetKey + ":" + seq, type.name(), card.id(), plan == null ? null : plan.id(), state,
                    rule.decision().name(), rule.version(), Status.HELD.name(), refundFen, paidFen, paid.isPresent() ? "SNAPSHOT" : "NO_SNAPSHOT",
                    note, applicant, rule.cardAction().name(), rule.poolAction() == null ? null : rule.poolAction().name(), rule.reverseCommission(),
                    now, now).update(keys);
            long caseId = keys.getKey().longValue();
            audit.record(applicant, "REFUND_APPLY", "REFUND_CASE", Long.toString(caseId), null,
                    "{\"target\":\"" + targetKey + "\",\"state\":\"" + state + "\",\"decision\":\"" + rule.decision() + "\",\"ruleVersion\":"
                            + rule.version() + ",\"refundFen\":" + refundFen + "}");
            return caseId;
        });
        if (rule.decision() == Decision.AUTO_APPROVE && settings.get(RefundSettings.AUTO_REVIEW)) {
            try {
                return approve(id, AUTO_REVIEWER);
            } catch (UserFacingException e) {
                audit.record(AUTO_REVIEWER, "REFUND_AUTO_APPROVE_FAILED", "REFUND_CASE", Long.toString(id), null,
                        "{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }
        return view(id);
    }

    /** 批准：按申请时定下的卡动作取消指令或请求停卡/终止（终止按能力降级），进入 EXECUTING。 */
    public RefundCaseView approve(long id, String actor) {
        tx.executeWithoutResult(status -> {
            CaseRow c = lockCase(id, Status.HELD, "审批");
            move(c, Status.APPROVED, actor, null);
            StatusRequest requested = switch (c.cardAction()) {
                case NONE -> null;
                case CANCEL -> {
                    cards.cancelPending(c.cardAssetId(), c.cardPlanId(), actor);
                    yield null;
                }
                case SUSPEND, TERMINATE -> cards.requestStatus(c.cardAssetId(), StatusOp.valueOf(c.cardAction().name()), actor);
            };
            move(lockCase(id, Status.APPROVED, "审批"), Status.EXECUTING, null, requested);
            audit.record(actor, "REFUND_APPROVE", "REFUND_CASE", Long.toString(id), null,
                    "{\"cardAction\":\"" + c.cardAction() + "\",\"statusRequest\":" + (requested == null ? "null" : "\"" + requested + "\"") + "}");
        });
        return view(id);
    }

    /** 驳回：解除冻结，RELEASED。 */
    public RefundCaseView reject(long id, String actor) {
        tx.executeWithoutResult(status -> {
            CaseRow c = lockCase(id, Status.HELD, "驳回");
            move(c, Status.RELEASED, actor, null);
            cards.release(c.cardAssetId(), actor);
            audit.record(actor, "REFUND_REJECT", "REFUND_CASE", Long.toString(id), null, null);
        });
        return view(id);
    }

    /**
     * 完成（钱已退）：整卡 → 交码前作废、否则终止，池卡按规则归还/作废（未定按作废）；加包 → 撤销该套餐（撤不了转人工）并解冻。
     * 随后推进履约行、按规则调佣金冲正，DONE。停卡指令还没执行完时拒绝，免得收尾把它取消掉。
     */
    public RefundCaseView complete(long id, String actor) {
        tx.executeWithoutResult(status -> {
            CaseRow c = lockCase(id, Status.EXECUTING, "完成");
            if (c.targetType() == TargetType.CARD) {
                CardAssetStatus now = cardTarget(c.cardAssetId()).orElseThrow().status();
                if (!statusRequestSettled(c.statusRequest(), c.cardAction(), now)) {
                    throw new UserFacingException("停卡指令还未执行完成，请稍后再完成退款");
                }
                RefundEnd end = BEFORE_UPSTREAM.contains(now) ? RefundEnd.CANCELLED : RefundEnd.TERMINATED;
                cards.finalizeRefund(c.cardAssetId(), end, c.poolAction() == null ? PoolDisposition.VOID : c.poolAction(), actor);
            } else {
                cards.finalizePlanRefund(c.cardPlanId(), actor);
                cards.release(c.cardAssetId(), actor);
            }
            cards.settleRefundedLine(c.cardAssetId(), c.cardPlanId(), actor);
            if (c.reverseCommission()) {
                reversal.reverse(c.id(), c.sourceLineRef(), c.refundFen(), c.paidFen());
            }
            move(c, Status.DONE, null, c.statusRequest());
            audit.record(actor, "REFUND_COMPLETE", "REFUND_CASE", Long.toString(id), null, "{\"refundFen\":" + c.refundFen() + "}");
        });
        return view(id);
    }

    public RefundCaseView view(long id) {
        return row(id).map(RefundCases::view).orElseThrow(() -> new UserFacingException("找不到这笔退款"));
    }

    /** 后台列表；{@code status} 可空。页码从 1 开始。 */
    public PageResult<RefundCaseView.Admin> page(String status, int pageNumber, int pageSize) {
        String where = "";
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            String s = status.trim();
            if (Arrays.stream(Status.values()).noneMatch(v -> v.name().equals(s))) {
                throw new UserFacingException("不认识的退款状态：" + s);
            }
            where = " WHERE rc.status = ?";
            args.add(s);
        }
        int size = PageResult.clampSize(pageSize);
        int current = Math.max(pageNumber, 1);
        long total = jdbc.sql("SELECT COUNT(*) FROM lr_refund_case rc" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(PageResult.offset(current, size));
        List<RefundCaseView.Admin> records = jdbc.sql(CASE_SELECT + where + " ORDER BY rc.id DESC LIMIT ? OFFSET ?").params(pageArgs)
                .query(CaseRow.class).list().stream().map(RefundCases::admin).toList();
        return new PageResult<>(records, total, current, size);
    }

    public static String statusText(Status status) {
        return switch (status) {
            case HELD -> "待审核";
            case APPROVED, EXECUTING -> "退款处理中";
            case DONE -> "已退款";
            case RELEASED -> "未通过";
        };
    }

    public static String decisionText(Decision decision) {
        return switch (decision) {
            case AUTO_APPROVE -> "建议通过";
            case NEED_APPROVAL -> "需人工审核";
            case DEFAULT_REJECT -> "建议驳回";
        };
    }

    private static String targetTypeText(TargetType type) {
        return switch (type) {
            case CARD -> "整卡";
            case PLAN -> "加包";
        };
    }

    /** 申请时状态：整卡存卡状态，加包存套餐状态。 */
    private static String stateAtApplyText(TargetType type, String state) {
        return switch (type) {
            case CARD -> CardViewProjector.statusText(CardAssetStatus.valueOf(state));
            case PLAN -> CardViewProjector.planStatusText(CardPlanStatus.valueOf(state));
        };
    }

    /** 首张卡吸收除不尽的分，行内各卡退款合计 = 行实付。 */
    private static long unitShare(long paidFen, int qty, int unitSeq) {
        if (qty <= 1) {
            return paidFen;
        }
        long share = paidFen / qty;
        return unitSeq == 1 ? paidFen - share * (qty - 1) : share;
    }

    private static boolean statusRequestSettled(StatusRequest request, CardAction action, CardAssetStatus now) {
        if (request == null || request == StatusRequest.LOCAL_ONLY) {
            return true;
        }
        if (request == StatusRequest.QUEUED && action == CardAction.TERMINATE) {
            return now == CardAssetStatus.TERMINATED;
        }
        return now == CardAssetStatus.SUSPENDED || now == CardAssetStatus.TERMINATED;
    }

    private Optional<CardTarget> cardTarget(long cardAssetId) {
        return jdbc.sql(CARD_TARGET).param(cardAssetId).query(CardTarget.class).optional();
    }

    private PlanTarget planTarget(long cardPlanId, long cardAssetId) {
        PlanTarget plan = jdbc.sql(PLAN_TARGET).param(cardPlanId).query(PlanTarget.class).optional().filter(p -> p.cardAssetId() == cardAssetId)
                .orElseThrow(() -> new UserFacingException("找不到这个加包"));
        if (!"TOP_UP".equals(plan.kind())) {
            throw new UserFacingException("首购套餐请整卡退款");
        }
        if (plan.status() == CardPlanStatus.CANCELLED) {
            throw new UserFacingException("这个加包已取消");
        }
        if (jdbc.sql("SELECT COUNT(*) FROM lr_refund_case WHERE card_plan_id = ? AND status = 'DONE'").param(plan.id()).query(Integer.class).single() > 0) {
            throw new UserFacingException("这个加包已退款");
        }
        return plan;
    }

    private boolean hasActiveCase(long cardAssetId) {
        return jdbc.sql("SELECT COUNT(*) FROM lr_refund_case WHERE card_asset_id = :card AND status IN (:active)")
                .param("card", cardAssetId).param("active", ACTIVE.stream().map(Enum::name).toList()).query(Integer.class).single() > 0;
    }

    private Optional<CaseRow> row(long id) {
        return jdbc.sql(CASE_SELECT + " WHERE rc.id = ?").param(id).query(CaseRow.class).optional();
    }

    private CaseRow lockCase(long id, Status expected, String action) {
        CaseRow c = jdbc.sql(CASE_SELECT + " WHERE rc.id = ? FOR UPDATE OF rc").param(id).query(CaseRow.class).optional()
                .orElseThrow(() -> new UserFacingException("找不到这笔退款"));
        if (c.status() != expected) {
            throw new UserFacingException("这笔退款" + statusText(c.status()) + "，不能" + action);
        }
        return c;
    }

    private void move(CaseRow c, Status to, String decidedBy, StatusRequest statusRequest) {
        Status.MACHINE.requireMove(c.status(), to);
        int updated = jdbc.sql("""
                UPDATE lr_refund_case
                   SET status = ?, decided_by = COALESCE(?, decided_by), status_request = ?, version = version + 1, updated_at = ?
                 WHERE id = ? AND status = ? AND version = ?
                """).params(to.name(), decidedBy, statusRequest == null ? null : statusRequest.name(), UtcColumns.toColumn(clock.instant()), c.id(),
                c.status().name(), c.version()).update();
        if (updated != 1) {
            throw new UserFacingException("这笔退款已被他人处理，请刷新");
        }
    }

    private static RefundCaseView view(CaseRow c) {
        return new RefundCaseView(c.id(), c.cardAssetId(), c.cardPlanId(), c.status().name(), statusText(c.status()), c.decision().name(),
                decisionText(c.decision()), c.refundFen(), c.reason(), DisplayTime.format(UtcColumns.fromColumn(c.createdAt())));
    }

    private static RefundCaseView.Admin admin(CaseRow c) {
        return new RefundCaseView.Admin(view(c), c.ownerMemberId(), c.cardNo(), c.targetType().name(), targetTypeText(c.targetType()),
                c.cardStateAtApply(), stateAtApplyText(c.targetType(), c.cardStateAtApply()), c.orderSn(), c.cardAction().name(),
                c.poolAction() == null ? null : c.poolAction().name(), c.reverseCommission(), c.refundBasis(), c.appliedBy(), c.decidedBy());
    }
}
