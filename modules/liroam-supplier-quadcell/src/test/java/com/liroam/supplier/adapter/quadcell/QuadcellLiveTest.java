package com.liroam.supplier.adapter.quadcell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.liroam.supplier.spi.CardRef;
import com.liroam.supplier.spi.Outcome;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.ProvisionResult;
import com.liroam.supplier.spi.StatusCommand;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.TopUpCommand;
import com.liroam.supplier.spi.TopUpResult;
import com.liroam.supplier.spi.UsageSnapshot;

/**
 * 真上游联调（Quadcell 没有独立沙箱，测试 key 也打正式网址、会动真卡）。没有 key 整类跳过。
 * <ul>
 *   <li>只读：{@code LIROAM_QUADCELL_LIVE_ICCID}（一张已开订阅的测试卡）。</li>
 *   <li>写：另需 {@code LIROAM_QUADCELL_LIVE_WRITES=true}、{@code LIROAM_QUADCELL_LIVE_IDLE_ICCID}（Idle 测试卡）、
 *       {@code LIROAM_QUADCELL_LIVE_PACK_CODE}；可选 {@code LIROAM_QUADCELL_LIVE_TOPUP_PACK_CODE}、
 *       {@code LIROAM_QUADCELL_LIVE_PAYLOAD}（如 {@code mode=1;autoAct=true}）。终止会作废卡，单独要
 *       {@code LIROAM_QUADCELL_LIVE_TERMINATE=true}。</li>
 * </ul>
 * 断言只看结果类型与脱敏，不打印卡号与 key。
 */
@EnabledIfEnvironmentVariable(named = "LIROAM_SUPPLIER_QUADCELL_APIKEY", matches = ".*\\S.*")
class QuadcellLiveTest {

    private QuadcellAdapter adapter;

    @BeforeEach
    void connect() {
        QuadcellProperties p = new QuadcellProperties();
        p.setApiKey(System.getenv("LIROAM_SUPPLIER_QUADCELL_APIKEY"));
        env("LIROAM_SUPPLIER_QUADCELL_BASEURL").ifPresent(p::setBaseUrl);
        env("LIROAM_SUPPLIER_QUADCELL_PROXYHOST").ifPresent(p::setProxyHost);
        env("LIROAM_SUPPLIER_QUADCELL_PROXYPORT").map(Integer::valueOf).ifPresent(p::setProxyPort);
        env("LIROAM_SUPPLIER_QUADCELL_DEFAULTMODE").map(Integer::valueOf).ifPresent(p::setDefaultMode);
        p.setReadTimeout(Duration.ofSeconds(30));
        adapter = new QuadcellAdapter(p);
    }

    private static Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name)).map(String::strip).filter(s -> !s.isEmpty());
    }

    private static String require(String name) {
        String value = env(name).orElse(null);
        assumeTrue(value != null, "未设置 " + name);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static <T> T ok(Outcome<T> outcome) {
        assertThat(outcome).as("上游结果（不含卡号与 key）").isInstanceOf(Outcome.Ok.class);
        return ((Outcome.Ok<T>) outcome).value();
    }

    @Test
    void readOnlyQueryOfAnExistingTestCard() {
        String iccid = require("LIROAM_QUADCELL_LIVE_ICCID");

        UsageSnapshot snapshot = ok(adapter.queryUsage(new CardRef(0, iccid, null)));

        assertThat(snapshot.status()).isNotNull();
        assertThat(snapshot.rawJson()).doesNotContain(iccid);
    }

    @Test
    void unknownCardIsRejectedNotRetried() {
        Outcome<UsageSnapshot> outcome = adapter.queryUsage(new CardRef(0, "8900000000000000000", null));
        assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LIROAM_QUADCELL_LIVE_WRITES", matches = "true")
    void provisionQueryTopUpAndOptionallyTerminateAnIdleTestCard() {
        String iccid = require("LIROAM_QUADCELL_LIVE_IDLE_ICCID");
        String pack = require("LIROAM_QUADCELL_LIVE_PACK_CODE");
        Map<String, String> payload = env("LIROAM_QUADCELL_LIVE_PAYLOAD").map(s -> Arrays.stream(s.split(";")).map(kv -> kv.split("=", 2))
                .filter(kv -> kv.length == 2).collect(Collectors.toMap(kv -> kv[0].strip(), kv -> kv[1].strip()))).orElse(Map.of());

        ProvisionResult provisioned = ok(adapter.provision(new ProvisionCommand("PROVISION-LIVE-0-1", 0, iccid, pack, payload)));
        CardRef card = new CardRef(0, iccid, provisioned.upstreamRef());
        UsageSnapshot after = ok(adapter.queryUsage(card));
        assertThat(after.plans()).extracting(UsageSnapshot.PlanUsage::upstreamPlanRef).contains(provisioned.upstreamRef());

        env("LIROAM_QUADCELL_LIVE_TOPUP_PACK_CODE").ifPresent(topUp -> {
            TopUpResult added = ok(adapter.addPlan(new TopUpCommand("ADD_PLAN-LIVE-0-1", card, topUp, Map.of())));
            assertThat(ok(adapter.queryUsage(card)).plans()).extracting(UsageSnapshot.PlanUsage::upstreamPlanRef).contains(added.upstreamPlanRef());
        });

        if (env("LIROAM_QUADCELL_LIVE_TERMINATE").filter("true"::equals).isPresent()) {
            ok(adapter.changeStatus(new StatusCommand("TERMINATE-LIVE-0-1", card, StatusOp.TERMINATE)));
        }
    }
}
