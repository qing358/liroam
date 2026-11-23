package com.liroam.refund.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.LiroamApiExceptionHandler;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.poll.CardRefresh;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.refund.AuditOnlyCommissionReversal;
import com.liroam.refund.RefundCases;
import com.liroam.refund.RefundRules;

/** 买家申请退款与后台退款/规则接口的 HTTP 形状：壳同款返回包、分页字段、后台行平铺、业务拒绝走 success=false + 中文提示。 */
class RefundControllersTest {

    static final class FakeActor implements CurrentActor {

        volatile String memberId;
        volatile String operator;

        @Override
        public String requireMemberId() {
            if (memberId == null) {
                throw new UserFacingException("请先登录");
            }
            return memberId;
        }

        @Override
        public String requireOperator() {
            if (operator == null) {
                throw new UserFacingException("请先登录");
            }
            return operator;
        }
    }

    private static FulfillmentHarness h;
    private static final FakeActor actor = new FakeActor();
    private static MockMvc buyer;
    private static MockMvc manager;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("refund_web");
        JdbcClient jdbc = h.bean(JdbcClient.class);
        AuditLog audit = h.bean(AuditLog.class);
        TransactionTemplate tx = new TransactionTemplate(h.bean(PlatformTransactionManager.class));
        RefundRules rules = new RefundRules(tx, jdbc, audit);
        RefundCases cases = new RefundCases(tx, jdbc, rules, h.bean(CardActions.class), h.bean(CardRefresh.class),
                new AuditOnlyCommissionReversal(audit), h.bean(Settings.class), audit, h.clock);
        buyer = MockMvcBuilders.standaloneSetup(new BuyerRefundController(actor, cases)).setControllerAdvice(new LiroamApiExceptionHandler()).build();
        manager = MockMvcBuilders.standaloneSetup(new ManagerRefundController(actor, cases, rules))
                .setControllerAdvice(new LiroamApiExceptionHandler()).build();
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
        h.jdbc.update("DELETE FROM lr_refund_case");
        h.jdbc.update("DELETE FROM lr_refund_rule");
        RefundRules.seedDevDefaults(h.bean(DataSource.class));
        actor.memberId = "m1";
        actor.operator = "manager:admin";
    }

    private long deliveredA(String order, String member) {
        h.importPoolA(1, true);
        long card = h.acceptCard(order, h.catalog.skuANew, member);
        h.drain();
        return card;
    }

    private static ResultActions ok(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.code").value(200));
    }

    private static ResultActions refused(ResultActions r, String message) throws Exception {
        return r.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(message)).andExpect(jsonPath("$.result").doesNotExist());
    }

    private static ResultActions applyRefund(long card, String body) throws Exception {
        return buyer.perform(post("/buyer/liroam/cards/{id}/refund", card).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void buyerAppliesForOwnCardAndGetsCaseView() throws Exception {
        long card = deliveredA("O-1", "m1");
        long other = deliveredA("O-2", "m2");

        ok(applyRefund(card, "{\"reason\":\"行程取消\"}"))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.result.id").isNumber())
                .andExpect(jsonPath("$.result.cardAssetId").value(card))
                .andExpect(jsonPath("$.result.cardPlanId").doesNotExist())
                .andExpect(jsonPath("$.result.status").value("HELD"))
                .andExpect(jsonPath("$.result.statusText").value("待审核"))
                .andExpect(jsonPath("$.result.decision").value("NEED_APPROVAL"))
                .andExpect(jsonPath("$.result.decisionText").value("需人工审核"))
                .andExpect(jsonPath("$.result.refundFen").value(0))
                .andExpect(jsonPath("$.result.reason").value("行程取消"))
                .andExpect(jsonPath("$.result.createdText").value("2026-11-22 12:00"));

        refused(applyRefund(card, "{}"), "这张卡已有退款在处理中");
        refused(applyRefund(other, "{}"), "找不到这张卡");
        actor.memberId = null;
        refused(applyRefund(card, "{}"), "请先登录");
    }

    @Test
    void managerListsApprovesAndCompletesRefunds() throws Exception {
        long card = deliveredA("O-3", "m1");
        long rejected = deliveredA("O-4", "m1");
        ok(applyRefund(card, "{}"));
        ok(applyRefund(rejected, "{}"));
        long id = h.jdbc.queryForObject("SELECT id FROM lr_refund_case WHERE card_asset_id = ?", Long.class, card);
        long rejectedId = h.jdbc.queryForObject("SELECT id FROM lr_refund_case WHERE card_asset_id = ?", Long.class, rejected);
        String cardNo = h.jdbc.queryForObject("SELECT card_no FROM lr_card_asset WHERE id = ?", String.class, card);

        ok(manager.perform(get("/manager/liroam/refunds").param("status", "HELD").param("pageNumber", "1").param("pageSize", "1")))
                .andExpect(jsonPath("$.result.total").value(2))
                .andExpect(jsonPath("$.result.current").value(1))
                .andExpect(jsonPath("$.result.size").value(1))
                .andExpect(jsonPath("$.result.records[0].id").value(rejectedId))
                .andExpect(jsonPath("$.result.records[0].refundCase").doesNotExist());
        ok(manager.perform(get("/manager/liroam/refunds").param("pageSize", "10")))
                .andExpect(jsonPath("$.result.records[1].id").value(id))
                .andExpect(jsonPath("$.result.records[1].statusText").value("待审核"))
                .andExpect(jsonPath("$.result.records[1].ownerMemberId").value("m1"))
                .andExpect(jsonPath("$.result.records[1].cardNo").value(cardNo))
                .andExpect(jsonPath("$.result.records[1].targetType").value("CARD"))
                .andExpect(jsonPath("$.result.records[1].targetTypeText").value("整卡"))
                .andExpect(jsonPath("$.result.records[1].cardStateAtApply").value("DELIVERED"))
                .andExpect(jsonPath("$.result.records[1].cardStateText").value("待激活"))
                .andExpect(jsonPath("$.result.records[1].orderSn").value("O-3"))
                .andExpect(jsonPath("$.result.records[1].cardAction").value("TERMINATE"))
                .andExpect(jsonPath("$.result.records[1].poolAction").value("VOID"))
                .andExpect(jsonPath("$.result.records[1].refundBasis").value("NO_SNAPSHOT"));
        refused(manager.perform(get("/manager/liroam/refunds").param("status", "OPEN")), "不认识的退款状态：OPEN");

        ok(manager.perform(post("/manager/liroam/refunds/{id}/reject", rejectedId))).andExpect(jsonPath("$.result.status").value("RELEASED"));
        ok(manager.perform(post("/manager/liroam/refunds/{id}/approve", id))).andExpect(jsonPath("$.result.status").value("EXECUTING"));
        refused(manager.perform(post("/manager/liroam/refunds/{id}/complete", id)), "停卡指令还未执行完成，请稍后再完成退款");
        h.drain();
        ok(manager.perform(post("/manager/liroam/refunds/{id}/complete", id))).andExpect(jsonPath("$.result.status").value("DONE"))
                .andExpect(jsonPath("$.result.statusText").value("已退款"));
        assertThat(h.count("lr_audit_log WHERE action = 'COMMISSION_REVERSAL_DEFERRED' AND target_id = ?", Long.toString(id))).isEqualTo(1);
        refused(manager.perform(post("/manager/liroam/refunds/{id}/approve", 999_999)), "找不到这笔退款");

        actor.operator = null;
        refused(manager.perform(get("/manager/liroam/refunds")), "请先登录");
        refused(manager.perform(post("/manager/liroam/refunds/{id}/approve", id)), "请先登录");
    }

    @Test
    void managerListShowsTopUpRefundWithPlanStateAndTopUpOrder() throws Exception {
        long card = deliveredA("O-5", "m1");
        long line = h.acceptTopUp("O-T5", "O-T5-1", h.catalog.skuATopUp, "m1", card);
        h.drain();
        long plan = h.jdbc.queryForObject("SELECT id FROM lr_card_plan WHERE fulfillment_line_id = ?", Long.class, line);
        h.jdbc.update("UPDATE lr_card_plan SET status = 'READY' WHERE id = ?", plan);
        ok(applyRefund(card, "{\"cardPlanId\":" + plan + "}"));

        ok(manager.perform(get("/manager/liroam/refunds")))
                .andExpect(jsonPath("$.result.records[0].cardPlanId").value(plan))
                .andExpect(jsonPath("$.result.records[0].targetType").value("PLAN"))
                .andExpect(jsonPath("$.result.records[0].targetTypeText").value("加包"))
                .andExpect(jsonPath("$.result.records[0].cardStateAtApply").value("READY"))
                .andExpect(jsonPath("$.result.records[0].cardStateText").value("未激活"))
                .andExpect(jsonPath("$.result.records[0].orderSn").value("O-T5"));
    }

    @Test
    void managerReadsAndSavesRulesAsCamelCaseJson() throws Exception {
        ok(manager.perform(get("/manager/liroam/refund-rules")))
                .andExpect(jsonPath("$.result.length()").value(14))
                .andExpect(jsonPath("$.result[2].id").isNumber())
                .andExpect(jsonPath("$.result[2].cardState").value("DELIVERED"))
                .andExpect(jsonPath("$.result[2].targetType").value("CARD"))
                .andExpect(jsonPath("$.result[2].decision").value("NEED_APPROVAL"))
                .andExpect(jsonPath("$.result[2].cardAction").value("TERMINATE"))
                .andExpect(jsonPath("$.result[2].poolAction").value("VOID"))
                .andExpect(jsonPath("$.result[2].reverseCommission").value(true))
                .andExpect(jsonPath("$.result[2].version").value(1));

        ok(manager.perform(post("/manager/liroam/refund-rules").contentType(MediaType.APPLICATION_JSON).content("""
                {"cardState":"DELIVERED","targetType":"CARD","decision":"AUTO_APPROVE","cardAction":"TERMINATE","poolAction":"VOID",
                 "reverseCommission":true,"version":1}
                """))).andExpect(jsonPath("$.result.version").value(2)).andExpect(jsonPath("$.result.decision").value("AUTO_APPROVE"));
        refused(manager.perform(post("/manager/liroam/refund-rules").contentType(MediaType.APPLICATION_JSON).content("""
                {"cardState":"DELIVERED","targetType":"CARD","decision":"AUTO_APPROVE","cardAction":"TERMINATE","version":1}
                """)), "这条规则已被修改，请刷新后再改");
        actor.operator = null;
        refused(manager.perform(post("/manager/liroam/refund-rules").contentType(MediaType.APPLICATION_JSON).content("{}")), "请先登录");
    }
}
