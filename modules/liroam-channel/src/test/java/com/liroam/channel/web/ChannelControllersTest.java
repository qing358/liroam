package com.liroam.channel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.liroam.channel.ChannelClaims;
import com.liroam.channel.ChannelDirectory;
import com.liroam.channel.ChannelOrders;
import com.liroam.channel.ChannelReconciliation;
import com.liroam.channel.ChannelSkuMappings;
import com.liroam.channel.ChannelWriteBacks;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.channel.spi.WriteBackOutcome;
import com.liroam.channel.testing.ChannelFixture;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.LiroamApiExceptionHandler;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.testing.MutableClock;

/** 后台渠道接口与买家认领接口的 HTTP 形状：壳同款返回包、分页、业务拒绝 success=false + 中文提示、未登录拒绝。 */
class ChannelControllersTest {

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

    private static ChannelFixture f;
    private static final FakeActor actor = new FakeActor();
    private static MockMvc buyer;
    private static MockMvc manager;

    @BeforeAll
    static void start() {
        f = ChannelFixture.start("channel_web");
        buyer = MockMvcBuilders.standaloneSetup(new BuyerChannelOrderController(actor, f.bean(ChannelClaims.class)))
                .setControllerAdvice(new LiroamApiExceptionHandler()).build();
        manager = MockMvcBuilders.standaloneSetup(new ManagerChannelController(actor, f.bean(ChannelDirectory.class), f.bean(ChannelSkuMappings.class),
                f.bean(ChannelOrders.class), f.bean(ChannelWriteBacks.class), f.bean(ChannelReconciliation.class)))
                .setControllerAdvice(new LiroamApiExceptionHandler()).build();
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
        actor.memberId = "m1";
        actor.operator = "manager:admin";
    }

    private static ResultActions ok(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.code").value(200));
    }

    private static ResultActions refused(ResultActions r, String message) throws Exception {
        return r.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(message));
    }

    private static ResultActions json(MockMvc mvc, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body)
            throws Exception {
        return mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void managerConfiguresChannelAndMappings() throws Exception {
        ok(manager.perform(get("/manager/liroam/channels")))
                .andExpect(jsonPath("$.result[0].code").value("CH_A"))
                .andExpect(jsonPath("$.result[0].adapterKey").value(ChannelFixture.ADAPTER))
                .andExpect(jsonPath("$.result[0].adapterWired").value(true))
                .andExpect(jsonPath("$.result[0].enabled").value(true))
                .andExpect(jsonPath("$.result[0].pullCron").value("5m"));
        ok(json(manager, put("/manager/liroam/channels/{id}", f.channelId),
                "{\"enabled\":true,\"pullCron\":\"2m\",\"buyerCanViewUsage\":true,\"commissionEnabled\":false}"))
                .andExpect(jsonPath("$.result.pullCron").value("2m"));
        refused(json(manager, put("/manager/liroam/channels/{id}", f.channelId), "{\"enabled\":true,\"pullCron\":\"x\"}"),
                "拉单间隔格式不对：x（如 5m、PT30S 或 6 段 cron）");

        ok(manager.perform(get("/manager/liroam/channels/{id}/sku-mappings", f.channelId)))
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].externalSkuId").value("EXT-A"));
        ok(json(manager, post("/manager/liroam/channels/{id}/sku-mappings", f.channelId), "{\"externalSkuId\":\"EXT-C\",\"liGoodsSkuId\":\"SKU-B\"}"))
                .andExpect(jsonPath("$.result.liGoodsSkuId").value("SKU-B"));
        long mapping = f.h.jdbc.queryForObject("SELECT id FROM lr_channel_sku_mapping WHERE external_sku_id = 'EXT-C'", Long.class);
        ok(json(manager, put("/manager/liroam/channels/sku-mappings/{id}", mapping), "{\"liGoodsSkuId\":\"SKU-A\",\"enabled\":false}"))
                .andExpect(jsonPath("$.result.enabled").value(false));
        ok(manager.perform(delete("/manager/liroam/channels/sku-mappings/{id}", mapping)));
        refused(json(manager, post("/manager/liroam/channels/{id}/sku-mappings", f.channelId), "{\"externalSkuId\":\"\",\"liGoodsSkuId\":\"SKU-B\"}"),
                "请填写渠道 SKU 与本站 SKU");

        actor.operator = null;
        refused(manager.perform(get("/manager/liroam/channels")), "请先登录");
    }

    @Test
    void managerPullsListsReplaysRetriesAndReconciles() throws Exception {
        f.h.importPoolA(1, true);
        f.adapter.addOrder("O-1", MutableClock.START, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.adapter.addOrder("O-2", MutableClock.START, "13800138000", new ExternalOrderLine("1", "EXT-X", 1, 4900));

        ok(manager.perform(post("/manager/liroam/channels/{id}/pull", f.channelId)))
                .andExpect(jsonPath("$.result.pulled").value(2)).andExpect(jsonPath("$.result.created").value(2))
                .andExpect(jsonPath("$.result.manual").value(1));
        f.h.drain();
        f.adapter.nextWriteBacks(new WriteBackOutcome.Rejected("X", "拒绝"));
        f.bean(ChannelWriteBacks.class).runOnce(10);

        ok(manager.perform(get("/manager/liroam/channels/orders").param("channelId", Long.toString(f.channelId)).param("pageSize", "10")))
                .andExpect(jsonPath("$.result.total").value(2))
                .andExpect(jsonPath("$.result.records[0].externalOrderId").value("O-2"))
                .andExpect(jsonPath("$.result.records[0].statusText").value("待人工处理"))
                .andExpect(jsonPath("$.result.records[1].lines[0].writebackStatus").value("MANUAL"))
                .andExpect(jsonPath("$.result.records[1].lines[0].writebackText").value("回传失败，待人工"));

        long lineId = f.h.jdbc.queryForObject("SELECT l.id FROM lr_channel_order_line l JOIN lr_channel_order o ON o.id = l.channel_order_id "
                + "WHERE o.external_order_id = 'O-1'", Long.class);
        ok(manager.perform(post("/manager/liroam/channels/order-lines/{id}/writeback-retry", lineId)));
        refused(manager.perform(post("/manager/liroam/channels/orders/{id}/replay", f.orderId("O-2"))), "渠道 SKU 未映射：EXT-X");
        f.mapSku("EXT-X", "SKU-B");
        ok(manager.perform(post("/manager/liroam/channels/orders/{id}/replay", f.orderId("O-2")))).andExpect(jsonPath("$.result.status").value("FULFILLING"));

        f.adapter.statement(java.time.LocalDate.of(2026, 11, 22), new ExternalStatementLine("O-1", 4900, "{}"));
        ok(manager.perform(post("/manager/liroam/channels/{id}/recon", f.channelId).param("day", "2026-11-22")))
                .andExpect(jsonPath("$.result.diffCount").value(1));
        long runId = f.h.jdbc.queryForObject("SELECT id FROM lr_recon_run", Long.class);
        ok(manager.perform(get("/manager/liroam/channels/recon-runs/{id}/diffs", runId)))
                .andExpect(jsonPath("$.result[0].diffKind").value("MISSING_THEIRS"))
                .andExpect(jsonPath("$.result[0].ourRef").value("O-2"));
        refused(manager.perform(post("/manager/liroam/channels/{id}/recon", f.channelId).param("day", "22/11")), "对账日期格式应为 yyyy-MM-dd");
    }

    @Test
    void buyerClaimsChannelOrder() throws Exception {
        f.h.importPoolA(1, true);
        f.adapter.addOrder("O-9", MutableClock.START, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.bean(ChannelOrders.class).pullNow(f.channelId, "op");
        f.h.drain();
        f.phones.bind("m1", "13800138000");

        refused(json(buyer, post("/buyer/liroam/channel-orders/claim"), "{\"orderNo\":\"O-9\"}"), "请填写渠道订单号与收货手机号");
        ok(json(buyer, post("/buyer/liroam/channel-orders/claim"), "{\"orderNo\":\"O-9\",\"phone\":\"13800138000\"}"))
                .andExpect(jsonPath("$.result.cardCount").value(1));
        refused(json(buyer, post("/buyer/liroam/channel-orders/claim"), "{\"orderNo\":\"O-9\",\"phone\":\"13800138000\"}"), "该订单已被认领");
        assertThat(f.h.count("lr_card_asset WHERE owner_member_id = 'm1'")).isEqualTo(1);
        actor.memberId = null;
        refused(json(buyer, post("/buyer/liroam/channel-orders/claim"), "{\"orderNo\":\"O-9\",\"phone\":\"13800138000\"}"), "请先登录");
    }
}
