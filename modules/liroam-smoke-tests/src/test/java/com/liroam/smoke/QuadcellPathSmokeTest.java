package com.liroam.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;

import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.card.CardKyc;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.credential.CredentialEntry;
import com.liroam.fulfillment.poll.CardRefresh;
import com.liroam.fulfillment.state.KycStatus;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.fulfillment.testing.TestCatalog.Upstreams;
import com.liroam.supplier.adapter.quadcell.QuadcellAdapter;
import com.liroam.supplier.adapter.quadcell.QuadcellStubUpstream;
import com.liroam.supplier.adapter.quadcell.QuadcellStubUpstream.Recorded;
import com.liroam.supplier.mock.MockShapeBAdapter;
import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.contract.UpstreamFixture.Failure;

/**
 * P3：供应商 A 换成真 Quadcell 适配器（上游为按 Open API 报文回放的桩），走与 mock 相同的唯一履约入口与指令 worker：
 * 池卡开订阅（首单写后超时 → 按卡状态查档，不重发）、人工交码、按需查档写镜像（MB→字节）、加包只动选中卡、上游 429 退避；
 * 每次上游调用都先过 MySQL 令牌桶（容量取能力声明 100/分）。
 */
@TestInstance(Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class QuadcellPathSmokeTest {

    private static final String MEMBER = "m-qc";
    private static final Upstreams UPSTREAMS = new Upstreams(QuadcellAdapter.ADAPTER_KEY, MockShapeBAdapter.ADAPTER_KEY,
            QuadcellStubUpstream.BASE_PACK, QuadcellStubUpstream.TOPUP_PACK, "5GB-7D", "2GB-7D");

    private final QuadcellStubUpstream upstream = QuadcellStubUpstream.start();
    private SmokeHarness h;
    private TestCatalog c;
    private List<String> pool;
    private long a1;
    private long a2;

    @BeforeAll
    void start() {
        h = new SmokeHarness("smoke_quadcell_path", UPSTREAMS,
                ctx -> ctx.registerBean(QuadcellAdapter.ADAPTER_KEY, QuadcellAdapter.class, () -> new QuadcellAdapter(upstream.properties())));
        c = h.catalog;
    }

    @AfterAll
    void stop() {
        h.close();
        upstream.close();
    }

    private String status(long card) {
        return h.jdbc.queryForObject("SELECT status FROM lr_card_asset WHERE id = ?", String.class, card);
    }

    private String iccidOf(long card) {
        String last4 = h.jdbc.queryForObject("SELECT iccid_last4 FROM lr_card_asset WHERE id = ?", String.class, card);
        return pool.stream().filter(i -> i.endsWith(last4)).findFirst().orElseThrow();
    }

    private List<String> callLog(String column) {
        return h.jdbc.queryForList("SELECT " + column + " FROM lr_supplier_call_log WHERE supplier_id = ? ORDER BY id", String.class, c.supplierA);
    }

    private long subscriptions() {
        return upstream.requests().stream().filter(r -> r.method().equals("POST") && r.path().matches(".*/sim-profile/\\d+$")).count();
    }

    @Test
    @Order(1)
    void poolCardsAreSubscribedOnceEvenWhenTheFirstWriteTimesOut() {
        pool = List.of(upstream.idleIccid(), upstream.idleIccid());
        h.importPoolA(pool);
        upstream.failNext(Failure.TIMEOUT_AFTER_SEND);

        h.acceptShop("LO-QC-1", "LO-QC-1-A", c.skuANew, 2, MEMBER);
        h.drain();

        List<Long> cards = h.jdbc.queryForList("SELECT id FROM lr_card_asset WHERE supplier_id = ? ORDER BY unit_seq", Long.class, c.supplierA);
        a1 = cards.get(0);
        a2 = cards.get(1);
        assertThat(List.of(status(a1), status(a2))).containsOnly("PROVISIONED");
        assertThat(pool).allSatisfy(iccid -> assertThat(upstream.status(iccid)).as("上游 Provisioned").isEqualTo(3));
        assertThat(subscriptions()).as("写后超时靠查档确认，不重发开订阅").isEqualTo(2);
        assertThat(callLog("outcome")).contains("UNKNOWN", "OK");
        assertThat(callLog("operation")).contains("PROVISION", "LOOKUP_PROVISION");
        assertThat(h.jdbc.queryForList("SELECT upstream_ref FROM lr_card_asset WHERE supplier_id = ?", String.class, c.supplierA))
                .as("8.7 回的 packId 作卡的上游引用").allSatisfy(ref -> assertThat(ref).startsWith("69ba6aec"));
        assertThat(h.count("lr_supplier_rate_bucket WHERE bucket_key = ?", "supplier:" + c.supplierA + ":100")).as("MySQL 令牌桶，容量 100/分")
                .isEqualTo(1);
        assertThat(h.jdbc.queryForList("SELECT CONCAT(COALESCE(request_redacted, ''), COALESCE(response_redacted, '')) FROM lr_supplier_call_log WHERE supplier_id = ?", String.class, c.supplierA))
                .as("调用日志不落卡号全号").allSatisfy(s -> pool.forEach(iccid -> assertThat(s).doesNotContain(iccid)));
    }

    @Test
    @Order(2)
    void manualCredentialThenOnDemandQueryMirrorsUpstreamUsage() {
        h.bean(CredentialEntry.class).enter(a1, "LPA:1$qc.example$A1", "op-qc");
        h.bean(CredentialEntry.class).enter(a2, "LPA:1$qc.example$A2", "op-qc");
        h.drain();
        assertThat(List.of(status(a1), status(a2))).containsOnly("DELIVERED");

        CardRef a1Upstream = new CardRef(a1, iccidOf(a1), null);
        upstream.activate(a1Upstream);
        upstream.seedUsage(a1Upstream);
        assertThat(h.bean(CardRefresh.class).requestOnDemand(a1)).isTrue();
        h.drain();

        assertThat(status(a1)).as("上游已激活且有用量").isEqualTo("IN_USE");
        assertThat(h.jdbc.queryForObject("SELECT remaining_bytes FROM lr_card_plan WHERE card_asset_id = ?", Long.class, a1))
                .isEqualTo(Math.round(1024.5 * 1024 * 1024));
        assertThat(h.jdbc.queryForObject("SELECT raw_json FROM lr_card_plan WHERE card_asset_id = ? ORDER BY id LIMIT 1", String.class, a1))
                .as("原始报文脱敏").doesNotContain(iccidOf(a1));
        assertThat(status(a2)).isEqualTo("DELIVERED");
    }

    @Test
    @Order(3)
    void topUpPostsPackageToTheChosenCardOnly() {
        h.bean(TopUpIntents.class).open(MEMBER, a1, "SKU-A-TOP");
        h.acceptTopUp("LO-QC-2", "LO-QC-2-1", c.skuATopUp, MEMBER, a1);
        h.drain();

        List<Recorded> packages = upstream.requests().stream().filter(r -> r.path().endsWith("/package")).toList();
        assertThat(packages).singleElement().satisfies(r -> {
            assertThat(r.path()).contains(iccidOf(a1));
            assertThat(r.json().path("packCode").asString()).isEqualTo(QuadcellStubUpstream.TOPUP_PACK);
        });
        assertThat(h.jdbc.queryForList("SELECT status FROM lr_card_plan WHERE card_asset_id = ? ORDER BY id", String.class, a1)).hasSize(2);
        assertThat(h.jdbc.queryForObject("SELECT upstream_plan_ref FROM lr_card_plan WHERE card_asset_id = ? ORDER BY id DESC LIMIT 1", String.class, a1))
                .startsWith("69ba6aec");
        assertThat(h.count("lr_card_plan WHERE card_asset_id = ?", a2)).as("另一张卡不受影响").isEqualTo(1);
    }

    @Test
    @Order(4)
    void upstreamThrottleBacksOffWithoutCountingAFailure() {
        upstream.failNext(Failure.THROTTLED);
        assertThat(h.bean(CardRefresh.class).requestOnDemand(a2)).isTrue();
        h.drain();

        assertThat(h.jdbc.queryForList("SELECT outcome FROM lr_supplier_call_log WHERE supplier_id = ? AND operation = 'QUERY' ORDER BY id",
                String.class, c.supplierA)).endsWith("THROTTLED", "OK");
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_supplier_command WHERE card_asset_id = ? AND type = 'QUERY' ORDER BY id DESC LIMIT 1",
                String.class, a2)).isEqualTo("SUCCEEDED");
    }

    @Test
    @Order(5)
    void realNameCardHoldsTheCodeUntilOperatorVerifies() {
        String iccid = upstream.idleIccid();
        h.importPoolA(List.of(iccid));
        h.jdbc.update("UPDATE lr_sku_binding SET kyc_gate_mode = 'BLOCK_DELIVERY' WHERE id = ?", c.skuANew);
        h.acceptShop("LO-QC-KYC", "LO-QC-KYC-A", c.skuANew, 1, MEMBER);
        h.drain();
        long card = h.jdbc.queryForObject("SELECT c.id FROM lr_card_asset c JOIN lr_fulfillment_line l ON l.id = c.fulfillment_line_id "
                + "WHERE l.source_ref = 'LO-QC-KYC'", Long.class);
        assertThat(upstream.status(iccid)).as("上游照常开订阅").isEqualTo(3);

        h.bean(CredentialEntry.class).enter(card, "LPA:1$qc.example$KYC", "op-qc");
        h.drain();
        assertThat(status(card)).as("码已入库但实名未过，不交付").isEqualTo("PROVISIONED");
        assertThat(h.bean(CardQueries.class).credentialForMember(card, MEMBER)).isEmpty();
        assertThat(h.shell.calls()).doesNotContain("LO-QC-KYC");

        h.bean(CardKyc.class).review(card, KycStatus.VERIFIED, "证件核验通过", "op-qc");
        h.drain();

        assertThat(status(card)).isEqualTo("DELIVERED");
        assertThat(h.bean(CardQueries.class).credentialForMember(card, MEMBER)).isPresent();
        assertThat(h.shell.calls()).containsOnlyOnce("LO-QC-KYC");
    }
}
