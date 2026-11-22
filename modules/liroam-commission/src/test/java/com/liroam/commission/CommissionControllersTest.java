package com.liroam.commission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.liroam.commission.AgentDirectory.AgentChain;
import com.liroam.commission.web.BuyerAgentController;
import com.liroam.commission.web.ManagerCommissionController;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.LiroamApiExceptionHandler;
import com.liroam.common.web.UserFacingException;

/** 代理端「我的佣金」与后台佣金接口的 HTTP 形状：壳同款返回包、分页字段、业务拒绝走 success=false + 中文提示、收款信息只给掩码。 */
class CommissionControllersTest {

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

    private static CommissionFixture f;
    private static final FakeActor actor = new FakeActor();
    private static MockMvc buyer;
    private static MockMvc manager;

    @BeforeAll
    static void start() {
        f = CommissionFixture.start("commission_web");
        buyer = MockMvcBuilders.standaloneSetup(new BuyerAgentController(actor, f.agents, f.ledger, f.withdrawals))
                .setControllerAdvice(new LiroamApiExceptionHandler()).build();
        manager = MockMvcBuilders.standaloneSetup(new ManagerCommissionController(actor, f.policies, f.ledger, f.withdrawals))
                .setControllerAdvice(new LiroamApiExceptionHandler()).build();
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
        actor.memberId = CommissionFixture.A1.memberId();
        actor.operator = "manager:admin";
    }

    private static ResultActions ok(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    private static ResultActions refused(ResultActions r, String messagePart) throws Exception {
        return r.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString(messagePart)));
    }

    private static ResultActions json(MockMvc mvc, String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void managerConfiguresPolicyAndFloorPrice() throws Exception {
        ok(manager.perform(get("/manager/liroam/commission/modes"))).andExpect(jsonPath("$.result[0].mode").value("PAID_RATE"))
                .andExpect(jsonPath("$.result[0].title").value("实付抽成"));
        ok(json(manager, "/manager/liroam/commission/policies", """
                {"code":"GLOBAL_DEFAULT","mode":"FLOOR_DIFF","scopeType":"GLOBAL","directRate":0.8,"inviteRate":0.2,
                 "settleDays":1,"settleAnchor":"DELIVERED","withdrawApproval":true}
                """)).andExpect(jsonPath("$.result.version").value(1));
        ok(json(manager, "/manager/liroam/commission/policies/GLOBAL_DEFAULT/floor-prices",
                "{\"agentGrade\":\"1\",\"skuBindingId\":" + f.c.skuANew + ",\"floorPriceFen\":7000}")).andExpect(jsonPath("$.result.version").value(2));
        ok(manager.perform(get("/manager/liroam/commission/policies"))).andExpect(jsonPath("$.result[0].policy.code").value("GLOBAL_DEFAULT"))
                .andExpect(jsonPath("$.result[0].policy.version").value(2)).andExpect(jsonPath("$.result[0].floorPrices[0].floorPriceFen").value(7000));
        refused(json(manager, "/manager/liroam/commission/policies", """
                {"code":"X","mode":"MYSTERY","scopeType":"GLOBAL","settleDays":1,"settleAnchor":"DELIVERED"}
                """), "佣金模式");
        actor.operator = null;
        refused(manager.perform(get("/manager/liroam/commission/policies")), "请先登录");
    }

    @Test
    void agentSeesBalanceAndWithdrawsThroughApprovalAndManualPayout() throws Exception {
        f.paidRate("0.10", "0.8", "0.2", 0);
        f.deliveredCard("O-WEB", 10_000, new AgentChain(CommissionFixture.A1, CommissionFixture.A2));
        f.accruals.runOnce(50);

        ok(buyer.perform(get("/buyer/liroam/agent/summary"))).andExpect(jsonPath("$.result.agent").value(true))
                .andExpect(jsonPath("$.result.availableFen").value(800)).andExpect(jsonPath("$.result.minWithdrawFen").value(100));
        ok(buyer.perform(get("/buyer/liroam/agent/entries"))).andExpect(jsonPath("$.result.total").value(1))
                .andExpect(jsonPath("$.result.records[0].typeText").value("佣金入账"));
        ok(json(buyer, "/buyer/liroam/agent/withdrawals", "{\"amountFen\":500,\"method\":\"ALIPAY\",\"name\":\"张三\",\"account\":\"13800001111\"}"))
                .andExpect(jsonPath("$.result.status").value("APPLIED")).andExpect(jsonPath("$.result.payeeMasked").value("支付宝 张* 138****1111"));
        refused(json(buyer, "/buyer/liroam/agent/withdrawals", "{\"amountFen\":500,\"method\":\"ALIPAY\",\"name\":\"张三\",\"account\":\"13800001111\"}"),
                "可提现");

        long id = f.h.jdbc.queryForObject("SELECT MAX(id) FROM lr_withdrawal", Long.class);
        ok(manager.perform(get("/manager/liroam/commission/withdrawals").param("status", "APPLIED"))).andExpect(jsonPath("$.result.total").value(1))
                .andExpect(jsonPath("$.result.records[0].agentName").value("一级代理"))
                .andExpect(jsonPath("$.result.records[0].payeeMasked").value("支付宝 张* 138****1111"));
        ok(manager.perform(get("/manager/liroam/commission/withdrawals/{id}/payee", id))).andExpect(jsonPath("$.result.account").value("13800001111"));
        ok(manager.perform(post("/manager/liroam/commission/withdrawals/{id}/approve", id))).andExpect(jsonPath("$.result.status").value("APPROVED"));
        ok(json(manager, "/manager/liroam/commission/withdrawals/" + id + "/paid", "{\"voucher\":\"回单 001\",\"note\":\"已转账\"}"))
                .andExpect(jsonPath("$.result.status").value("PAID")).andExpect(jsonPath("$.result.voucher").value("回单 001"));
        ok(buyer.perform(get("/buyer/liroam/agent/withdrawals"))).andExpect(jsonPath("$.result[0].statusText").value("已打款"));
        ok(manager.perform(get("/manager/liroam/commission/balances"))).andExpect(jsonPath("$.result.records[0].withdrawnFen").value(500));
        ok(manager.perform(get("/manager/liroam/commission/entries").param("agentRef", "D1"))).andExpect(jsonPath("$.result.total").value(2));
    }

    @Test
    void nonAgentIsToldPlainly() throws Exception {
        actor.memberId = "m-nobody";
        ok(buyer.perform(get("/buyer/liroam/agent/summary"))).andExpect(jsonPath("$.result.agent").value(false));
        refused(buyer.perform(get("/buyer/liroam/agent/entries")), "分销员");
        refused(json(manager, "/manager/liroam/commission/withdrawals/999/reject", "{\"note\":\"\"}"), "原因");
    }
}
