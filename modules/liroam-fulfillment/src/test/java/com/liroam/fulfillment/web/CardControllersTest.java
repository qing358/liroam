package com.liroam.fulfillment.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.LiroamApiExceptionHandler;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.card.CardKyc;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.courier.CardTracking;
import com.liroam.fulfillment.courier.CourierTracking;
import com.liroam.fulfillment.courier.mock.MockCourierTracking;
import com.liroam.fulfillment.delivery.MultiWaybillMode;
import com.liroam.fulfillment.store.CardCredentials;
import com.liroam.fulfillment.credential.CredentialEntry;
import com.liroam.fulfillment.credential.CredentialImports;
import com.liroam.fulfillment.poll.CardRefresh;
import com.liroam.fulfillment.pool.CardPool;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;

/** 买家/后台卡接口的 HTTP 形状：壳同款返回包、分页字段、后台视图平铺、业务拒绝走 success=false + 中文提示。 */
class CardControllersTest {

    /** 假登录态：memberId / operator 为 null 时按未登录拒绝。 */
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
    private static TestCatalog c;
    private static final FakeActor actor = new FakeActor();
    private static MockMvc buyer;
    private static MockMvc manager;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_web");
        c = h.catalog;
        CardTracking tracking = new CardTracking(h.bean(JdbcClient.class), h.bean(CardCredentials.class), h.bean(Settings.class),
                () -> List.of(new MockCourierTracking(h.clock), new SilentCourier()));
        buyer = MockMvcBuilders.standaloneSetup(new BuyerCardController(actor, h.bean(CardQueries.class), h.bean(CardRefresh.class),
                h.bean(TopUpIntents.class), h.bean(CardKyc.class), tracking)).setControllerAdvice(new LiroamApiExceptionHandler()).build();
        manager = MockMvcBuilders.standaloneSetup(new ManagerCardController(actor, h.bean(CardQueries.class), h.bean(CredentialEntry.class),
                h.bean(CredentialImports.class), h.bean(CardPool.class), h.bean(ManualTasks.class), h.bean(CardKyc.class), tracking,
                h.bean(Settings.class)))
                .setControllerAdvice(new LiroamApiExceptionHandler()).build();
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
        actor.memberId = "m1";
        actor.operator = "manager:admin";
    }

    private long deliveredA(String order, String member) {
        h.importPoolA(1, true);
        long card = h.acceptCard(order, c.skuANew, member);
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

    @Test
    void buyerListsOwnCardsAndDetailTriggersOnDemandRefresh() throws Exception {
        long card = deliveredA("O-1", "m1");
        long other = deliveredA("O-2", "m2");

        ok(buyer.perform(get("/buyer/liroam/cards")))
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].id").value(card))
                .andExpect(jsonPath("$.result[0].cardNo").value(startsWith("LR")))
                .andExpect(jsonPath("$.result[0].productName").value("亚洲漫游卡"))
                .andExpect(jsonPath("$.result[0].usageRegionName").value("日本"))
                .andExpect(jsonPath("$.result[0].form").value("ESIM"))
                .andExpect(jsonPath("$.result[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.result[0].statusText").value("待激活"))
                .andExpect(jsonPath("$.result[0].remainingText").value("3 GB"))
                .andExpect(jsonPath("$.result[0].expiresText").value("—"))
                .andExpect(jsonPath("$.result[0].updatedText").isString())
                .andExpect(jsonPath("$.result[0].canTopUp").value(true))
                .andExpect(jsonPath("$.result[0].canRefund").value(true))
                .andExpect(jsonPath("$.result[0].refundHold").value(false))
                .andExpect(jsonPath("$.result[0].iccidLast4").isString())
                .andExpect(jsonPath("$.result[0].supplierCode").value("SUP_A"))
                .andExpect(jsonPath("$.result[0].plans[0].name").value("日本 5 天 3GB"))
                .andExpect(jsonPath("$.result[0].plans[0].status").value("READY"))
                .andExpect(jsonPath("$.result[0].plans[0].statusText").value("未激活"))
                .andExpect(jsonPath("$.result[0].plans[0].remainingText").value("3 GB"))
                .andExpect(jsonPath("$.result[0].plans[0].expiresText").value("激活后 5 天有效"))
                .andExpect(jsonPath("$.result[0].ownerMemberId").doesNotExist());

        ok(buyer.perform(get("/buyer/liroam/cards/{id}", card))).andExpect(jsonPath("$.result.id").value(card));
        assertThat(h.command("QUERY", card)).as("详情顺带按需刷新").containsEntry("priority", 1);
        refused(buyer.perform(get("/buyer/liroam/cards/{id}", other)), "找不到这张卡");
        assertThat(h.count("lr_supplier_command WHERE type = 'QUERY' AND card_asset_id = ?", other)).isZero();

        actor.memberId = null;
        refused(buyer.perform(get("/buyer/liroam/cards")), "请先登录");
    }

    @Test
    void buyerCredentialTopUpOptionsAndIntent() throws Exception {
        long card = deliveredA("O-3", "m1");
        long fresh = h.acceptCard("O-4", c.skuANew, "m1");

        ok(buyer.perform(get("/buyer/liroam/cards/{id}/credential", card)))
                .andExpect(jsonPath("$.result.kind").value("LPA"))
                .andExpect(jsonPath("$.result.payload").value(startsWith("LPA:1$pool.example$")))
                .andExpect(jsonPath("$.result.channel").value("IMPORT"))
                .andExpect(jsonPath("$.result.deliveredText").value("2026-11-22 12:00"));
        ok(buyer.perform(get("/buyer/liroam/cards/{id}/credential", fresh))).andExpect(jsonPath("$.result").doesNotExist());

        ok(buyer.perform(get("/buyer/liroam/cards/{id}/topup-options", card)))
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].skuId").value("SKU-A-TOP"))
                .andExpect(jsonPath("$.result[0].goodsId").value("G-A-TOP"))
                .andExpect(jsonPath("$.result[0].name").value("日本加油包 1GB"))
                .andExpect(jsonPath("$.result[0].priceFen").value(1990));

        refused(buyer.perform(post("/buyer/liroam/cards/{id}/topup-intents", card).contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"SKU-B-TOP\"}")), "这个加包与该卡不匹配，请选择同一卡型的加包");
        refused(buyer.perform(post("/buyer/liroam/cards/{id}/topup-intents", card).contentType(MediaType.APPLICATION_JSON)
                .content("{}")), "请选择加包");
        ok(buyer.perform(post("/buyer/liroam/cards/{id}/topup-intents", card).contentType(MediaType.APPLICATION_JSON)
                .content("{\"skuId\":\"SKU-A-TOP\"}")))
                .andExpect(jsonPath("$.result.intentId").isNumber())
                .andExpect(jsonPath("$.result.skuId").value("SKU-A-TOP"))
                .andExpect(jsonPath("$.result.goodsId").value("G-A-TOP"));
        assertThat(h.count("lr_topup_intent WHERE status = 'OPEN' AND card_asset_id = ?", card)).isEqualTo(1);
    }

    @Test
    void managerPageIsFlatAndDetailCarriesCommands() throws Exception {
        long a = deliveredA("O-5", "m1");
        long b = h.acceptCard("O-6", c.skuBNew, "m2");
        h.drain();

        ok(manager.perform(get("/manager/liroam/cards").param("pageNumber", "1").param("pageSize", "10")))
                .andExpect(jsonPath("$.result.total").value(2))
                .andExpect(jsonPath("$.result.current").value(1))
                .andExpect(jsonPath("$.result.size").value(10))
                .andExpect(jsonPath("$.result.records[0].id").value(b))
                .andExpect(jsonPath("$.result.records[0].statusText").value("待激活"))
                .andExpect(jsonPath("$.result.records[0].ownerMemberId").value("m2"))
                .andExpect(jsonPath("$.result.records[0].orderSn").value("O-6"))
                .andExpect(jsonPath("$.result.records[0].createdText").isString())
                .andExpect(jsonPath("$.result.records[0].card").doesNotExist());
        ok(manager.perform(get("/manager/liroam/cards").param("supplierCode", "SUP_A").param("keyword", "m1")))
                .andExpect(jsonPath("$.result.total").value(1)).andExpect(jsonPath("$.result.records[0].id").value(a));

        ok(manager.perform(get("/manager/liroam/cards/{id}", a)))
                .andExpect(jsonPath("$.result.id").value(a))
                .andExpect(jsonPath("$.result.ownerMemberId").value("m1"))
                .andExpect(jsonPath("$.result.commands[*].type").value(hasItem("PROVISION")))
                .andExpect(jsonPath("$.result.commands[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.commands[0].attempts").isNumber());
        refused(manager.perform(get("/manager/liroam/cards/{id}", 999_999)), "找不到这张卡");

        actor.operator = null;
        refused(manager.perform(get("/manager/liroam/cards")), "请先登录");
    }

    @Test
    void managerImportsPoolAndCredentialsAndEntersCredentialByHand() throws Exception {
        String idle = h.upstreamA.idleIccid();
        ok(manager.perform(multipart("/manager/liroam/cards/pool/import")
                .file(new MockMultipartFile("file", "pool.csv", "text/csv", ("iccid,lpa\n" + idle + "\n").getBytes(StandardCharsets.UTF_8)))
                .param("supplierId", Long.toString(c.supplierA)).param("cardProductId", Long.toString(c.productA))))
                .andExpect(jsonPath("$.result.imported").value(1))
                .andExpect(jsonPath("$.result.duplicates").value(0))
                .andExpect(jsonPath("$.result.invalid.length()").value(0));
        refused(manager.perform(multipart("/manager/liroam/cards/pool/import")
                .file(new MockMultipartFile("file", "pool.csv", "text/csv", new byte[0]))
                .param("supplierId", Long.toString(c.supplierA)).param("cardProductId", Long.toString(c.productA))), "请上传 CSV 文件");

        long byImport = h.acceptCard("O-7", c.skuANew, "m1");
        h.drain();
        assertThat(h.status("lr_card_asset", byImport)).as("池卡无码，开通后等交码").isEqualTo("PROVISIONED");
        ok(manager.perform(multipart("/manager/liroam/cards/credentials/import")
                .file(new MockMultipartFile("file", "codes.csv", "text/csv", (idle + ",LPA:1$imp.example$X\n1234,\n").getBytes(StandardCharsets.UTF_8)))))
                .andExpect(jsonPath("$.result.delivered").value(1))
                .andExpect(jsonPath("$.result.pooled").value(0))
                .andExpect(jsonPath("$.result.unmatched[0].lineNo").value(2))
                .andExpect(jsonPath("$.result.unmatched[0].reason").isString());
        assertThat(h.status("lr_card_asset", byImport)).isEqualTo("DELIVERED");

        List<String> noCode = h.importPoolA(1, false);
        long byHand = h.acceptCard("O-8", c.skuANew, "m1");
        h.drain();
        assertThat(noCode).hasSize(1);
        refused(manager.perform(post("/manager/liroam/cards/{id}/credential", byHand).contentType(MediaType.APPLICATION_JSON).content("{}")),
                "请填写激活码");
        ok(manager.perform(post("/manager/liroam/cards/{id}/credential", byHand).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lpa\":\"LPA:1$hand.example$Y\"}")));
        assertThat(h.status("lr_card_asset", byHand)).isEqualTo("DELIVERED");
        assertThat(h.jdbc.queryForObject("SELECT channel FROM lr_card_credential WHERE card_asset_id = ?", String.class, byHand)).isEqualTo("MANUAL");
    }

    @Test
    void managerListsAndResolvesManualTasks() throws Exception {
        long task = h.bean(ManualTasks.class).open(ManualTasks.Kind.RECON_DIFF, "CARD", 42, "请核对", Instant.parse("2026-11-22T04:00:00Z"));

        ok(manager.perform(get("/manager/liroam/cards/manual-tasks").param("status", "OPEN")))
                .andExpect(jsonPath("$.result[0].id").value(task))
                .andExpect(jsonPath("$.result[0].kind").value("RECON_DIFF"))
                .andExpect(jsonPath("$.result[0].refType").value("CARD"))
                .andExpect(jsonPath("$.result[0].refId").value("42"))
                .andExpect(jsonPath("$.result[0].status").value("OPEN"))
                .andExpect(jsonPath("$.result[0].note").value("请核对"))
                .andExpect(jsonPath("$.result[0].createdText").value("2026-11-22 12:00"));

        ok(manager.perform(post("/manager/liroam/cards/manual-tasks/{id}/resolve", task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"已与供应商核对\"}")));
        refused(manager.perform(post("/manager/liroam/cards/manual-tasks/{id}/resolve", task).contentType(MediaType.APPLICATION_JSON)
                .content("{}")), "这条待办已处理");
        ok(manager.perform(get("/manager/liroam/cards/manual-tasks").param("status", "OPEN"))).andExpect(jsonPath("$.result.length()").value(0));
        ok(manager.perform(get("/manager/liroam/cards/manual-tasks"))).andExpect(jsonPath("$.result[0].status").value("DONE"))
                .andExpect(jsonPath("$.result[0].note").value("请核对\n已与供应商核对"));
    }

    @Test
    void buyerDeclaresKycAndManagerReviewsAndEntersShipment() throws Exception {
        h.jdbc.update("UPDATE lr_sku_binding SET kyc_gate_mode = 'BLOCK_DELIVERY' WHERE id = ?", c.skuBNew);
        long blocked = h.acceptCard("O-9", c.skuBNew, "m1");
        h.drain();

        ok(buyer.perform(get("/buyer/liroam/cards/{id}", blocked)))
                .andExpect(jsonPath("$.result.statusText").value("待实名"))
                .andExpect(jsonPath("$.result.capabilityText").value("流量 · 语音 · 短信"))
                .andExpect(jsonPath("$.result.voiceRemainingText").value("100 分钟"))
                .andExpect(jsonPath("$.result.smsRemainingText").value("50 条"))
                .andExpect(jsonPath("$.result.kycBlocking").value(true))
                .andExpect(jsonPath("$.result.canDeclareKyc").value(true));
        ok(buyer.perform(post("/buyer/liroam/cards/{id}/kyc-declaration", blocked)))
                .andExpect(jsonPath("$.result.kycText").value("已提交实名"))
                .andExpect(jsonPath("$.result.canDeclareKyc").value(false));
        h.drain();
        assertThat(h.status("lr_card_asset", blocked)).isEqualTo("DELIVERED");

        refused(manager.perform(post("/manager/liroam/cards/{id}/kyc", blocked).contentType(MediaType.APPLICATION_JSON).content("{}")),
                "核验结果只能是通过或驳回");
        ok(manager.perform(post("/manager/liroam/cards/{id}/kyc", blocked).contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"VERIFIED\",\"note\":\"供应商回执已核\"}")));
        assertThat(h.jdbc.queryForObject("SELECT kyc_status FROM lr_card_asset WHERE id = ?", String.class, blocked)).isEqualTo("VERIFIED");

        h.importPoolA(1, false, "PHYSICAL");
        long sim = h.acceptCard("O-10", c.skuAPhysical, "m1");
        h.drain();
        refused(manager.perform(post("/manager/liroam/cards/{id}/shipment", sim).contentType(MediaType.APPLICATION_JSON)
                .content("{\"logisticsId\":\"LOG-SF\",\"logisticsName\":\"顺丰速运\"}")), "请选择物流公司并填写运单号");
        ok(manager.perform(post("/manager/liroam/cards/{id}/shipment", sim).contentType(MediaType.APPLICATION_JSON)
                .content("{\"logisticsId\":\"LOG-SF\",\"logisticsName\":\"顺丰速运\",\"logisticsNo\":\"SF1\"}")));
        h.drain();
        ok(buyer.perform(get("/buyer/liroam/cards/{id}/credential", sim)))
                .andExpect(jsonPath("$.result.kind").value("SHIPMENT"))
                .andExpect(jsonPath("$.result.payload").doesNotExist())
                .andExpect(jsonPath("$.result.logisticsCompany").value("顺丰速运"))
                .andExpect(jsonPath("$.result.logisticsNo").value("SF1"));
    }

    private long shippedSim(String order, String member, String waybill) throws Exception {
        h.importPoolA(1, false, "PHYSICAL");
        long sim = h.acceptCard(order, c.skuAPhysical, member);
        h.drain();
        ok(manager.perform(post("/manager/liroam/cards/{id}/shipment", sim).contentType(MediaType.APPLICATION_JSON)
                .content("{\"logisticsId\":\"LOG-SF\",\"logisticsName\":\"顺丰速运\",\"logisticsNo\":\"" + waybill + "\"}")));
        return sim;
    }

    @Test
    void buyerAndManagerSeeCourierTrackFromConfiguredSource() throws Exception {
        h.bean(Settings.class).set(FulfillmentSettings.LOGISTICS_TRACKING_SOURCE, SettingScope.GLOBAL, MockCourierTracking.KEY, "manager:admin");
        long sim = shippedSim("O-11", "m1", "SF11");
        long esim = deliveredA("O-12", "m1");
        h.clock.advance(Duration.ofHours(25));

        ok(buyer.perform(get("/buyer/liroam/cards/{id}/tracking", sim)))
                .andExpect(jsonPath("$.result.logisticsCompany").value("顺丰速运"))
                .andExpect(jsonPath("$.result.logisticsNo").value("SF11"))
                .andExpect(jsonPath("$.result.shippedText").value("2026-11-22 12:00"))
                .andExpect(jsonPath("$.result.message").doesNotExist())
                .andExpect(jsonPath("$.result.events.length()").value(3))
                .andExpect(jsonPath("$.result.events[0].timeText").value("2026-11-23 12:00"))
                .andExpect(jsonPath("$.result.events[0].description").value("派送中：快递员正在派送"))
                .andExpect(jsonPath("$.result.events[2].description").value("已揽收：快递员已取件"));
        ok(buyer.perform(get("/buyer/liroam/cards/{id}/tracking", esim))).andExpect(jsonPath("$.result").doesNotExist());
        actor.memberId = "m2";
        refused(buyer.perform(get("/buyer/liroam/cards/{id}/tracking", sim)), "找不到这张卡");

        h.clock.advance(Duration.ofHours(12));
        ok(manager.perform(get("/manager/liroam/cards/{id}/tracking", sim)))
                .andExpect(jsonPath("$.result.events.length()").value(4))
                .andExpect(jsonPath("$.result.events[0].description").value("已签收：本人签收"));
        refused(manager.perform(get("/manager/liroam/cards/{id}/tracking", 999_999)), "找不到这张卡");
    }

    @Test
    void trackingFallsBackToWaybillWhenSourceMissingOrSilent() throws Exception {
        long sim = shippedSim("O-13", "m1", "SF13");

        assertThat(h.bean(Settings.class).get(FulfillmentSettings.LOGISTICS_TRACKING_SOURCE)).as("默认走壳物流插件").isEqualTo("shell");
        ok(buyer.perform(get("/buyer/liroam/cards/{id}/tracking", sim)))
                .andExpect(jsonPath("$.result.logisticsNo").value("SF13"))
                .andExpect(jsonPath("$.result.events.length()").value(0))
                .andExpect(jsonPath("$.result.message").value("物流查询暂不可用，请凭运单号到快递公司官网查询"));

        h.bean(Settings.class).set(FulfillmentSettings.LOGISTICS_TRACKING_SOURCE, SettingScope.GLOBAL, SilentCourier.KEY, "manager:admin");
        ok(buyer.perform(get("/buyer/liroam/cards/{id}/tracking", sim)))
                .andExpect(jsonPath("$.result.events.length()").value(0))
                .andExpect(jsonPath("$.result.message").value("暂未查到物流轨迹，快递揽收后通常数小时内更新"));
    }

    @Test
    void managerReadsAndUpdatesShippingSettings() throws Exception {
        ok(manager.perform(get("/manager/liroam/cards/settings/shipping")))
                .andExpect(jsonPath("$.result.multiWaybillMode").value("MANUAL"))
                .andExpect(jsonPath("$.result.trackingSource").value("shell"))
                .andExpect(jsonPath("$.result.trackingSources[0]").value("mock"))
                .andExpect(jsonPath("$.result.trackingSources[1]").value("silent"));

        refused(manager.perform(put("/manager/liroam/cards/settings/shipping").contentType(MediaType.APPLICATION_JSON)
                .content("{\"multiWaybillMode\":\"SPLIT\",\"trackingSource\":\"nope\"}")), "未装配物流轨迹来源「nope」");
        assertThat(h.bean(Settings.class).get(FulfillmentSettings.SHIPPING_MULTI_WAYBILL_MODE)).as("整笔不改").isEqualTo(MultiWaybillMode.MANUAL);

        ok(manager.perform(put("/manager/liroam/cards/settings/shipping").contentType(MediaType.APPLICATION_JSON)
                .content("{\"multiWaybillMode\":\"SPLIT\",\"trackingSource\":\"mock\"}")))
                .andExpect(jsonPath("$.result.multiWaybillMode").value("SPLIT"))
                .andExpect(jsonPath("$.result.trackingSource").value("mock"));
        ok(manager.perform(put("/manager/liroam/cards/settings/shipping").contentType(MediaType.APPLICATION_JSON)
                .content("{\"multiWaybillMode\":\"MANUAL\"}")))
                .andExpect(jsonPath("$.result.multiWaybillMode").value("MANUAL"))
                .andExpect(jsonPath("$.result.trackingSource").value("mock"));
        assertThat(h.count("lr_audit_log WHERE actor = 'manager:admin' AND target_id LIKE 'shipping.multi_waybill_mode@%'")).isEqualTo(2);

        actor.operator = null;
        refused(manager.perform(get("/manager/liroam/cards/settings/shipping")), "请先登录");
    }

    /** 查不到轨迹的来源（壳插件未配置或快递接口无数据时的样子）。 */
    static final class SilentCourier implements CourierTracking {

        static final String KEY = "silent";

        @Override
        public String key() {
            return KEY;
        }

        @Override
        public Optional<Track> track(Waybill waybill) {
            return Optional.empty();
        }
    }
}
