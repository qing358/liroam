package com.liroam.fulfillment.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;

/** 拆卡 worker：按数量建卡、首条指令按库存模式分支、加包挂原卡、重入幂等（CT-FUL-01、V3、V4）。 */
class FulfillmentWorkerTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static FulfillmentWorker worker;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_worker");
        c = h.catalog;
        worker = h.bean(FulfillmentWorker.class);
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    @Test
    void ct_ful_01_repeatedAcceptAndRepeatedWorkerRunsYieldOneLineAndNCards() {
        for (int i = 0; i < 10; i++) {
            h.acceptShop("O-1", "OI-1", c.skuANew, 3, "m1");
        }
        for (int i = 0; i < 5; i++) {
            worker.runOnce(10);
            h.acceptShop("O-1", "OI-1", c.skuANew, 3, "m1");
        }

        assertThat(h.count("lr_fulfillment_line")).isEqualTo(1);
        assertThat(h.jdbc.queryForList("SELECT unit_seq FROM lr_card_asset ORDER BY unit_seq", Integer.class)).containsExactly(1, 2, 3);
        assertThat(h.count("lr_card_plan WHERE kind = 'BASE' AND status = 'PENDING'")).isEqualTo(3);
        assertThat(h.count("lr_supplier_command")).isEqualTo(3);
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_fulfillment_line", String.class)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void ct_ful_01_concurrentWorkersDoNotDoubleExpand() throws Exception {
        h.acceptShop("O-2", "OI-2", c.skuANew, 4, "m1");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> runs = List.of(() -> worker.runOnce(5), () -> worker.runOnce(5), () -> worker.runOnce(5), () -> worker.runOnce(5));
            int expanded = 0;
            for (var f : pool.invokeAll(runs)) {
                expanded += f.get();
            }
            assertThat(expanded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(h.count("lr_card_asset")).isEqualTo(4);
        assertThat(h.count("lr_supplier_command")).isEqualTo(4);
    }

    @Test
    void v3_mixedCartYieldsThreeCardsWithOwnSupplierRegionAndFirstCommand() {
        h.acceptShop("O-3", "OI-3A", c.skuANew, 2, "m1");
        h.acceptShop("O-3", "OI-3B", c.skuBNew, 1, "m1");

        assertThat(worker.runOnce(10)).isEqualTo(2);

        List<Map<String, Object>> cards = h.jdbc.queryForList("""
                SELECT c.supplier_id, c.region_id, c.card_product_id, c.form, c.owner_member_id, c.status, c.card_no, l.source_line_ref, m.type, m.request_no
                  FROM lr_card_asset c JOIN lr_fulfillment_line l ON l.id = c.fulfillment_line_id
                  JOIN lr_supplier_command m ON m.card_asset_id = c.id
                 ORDER BY l.source_line_ref, c.unit_seq
                """);
        assertThat(cards).hasSize(3);
        assertThat(cards).extracting(r -> r.get("source_line_ref")).containsExactly("OI-3A", "OI-3A", "OI-3B");
        assertThat(cards).extracting(r -> ((Number) r.get("supplier_id")).longValue()).containsExactly(c.supplierA, c.supplierA, c.supplierB);
        assertThat(cards).extracting(r -> ((Number) r.get("region_id")).longValue()).containsExactly(c.regionJp, c.regionJp, c.regionUs);
        assertThat(cards).extracting(r -> ((Number) r.get("card_product_id")).longValue()).containsExactly(c.productA, c.productA, c.productB);
        assertThat(cards).extracting(r -> r.get("type")).as("池模式先本地分配，下单即建直接开通").containsExactly("ALLOCATE", "ALLOCATE", "PROVISION");
        assertThat(cards).allSatisfy(r -> {
            assertThat(r.get("owner_member_id")).isEqualTo("m1");
            assertThat(r.get("status")).isEqualTo("CREATED");
            assertThat(r.get("form")).isEqualTo("ESIM");
            assertThat((String) r.get("card_no")).matches("LR\\d{10}");
            assertThat((String) r.get("request_no")).matches(r.get("type") + "-\\d+-\\d+-1");
        });
    }

    @Test
    void v4_redeliveredPaymentAfterExpansionAddsNoCards() {
        h.acceptShop("O-4", "OI-4", c.skuBNew, 2, "m1");
        worker.runOnce(10);

        h.acceptShop("O-4", "OI-4", c.skuBNew, 2, "m1");
        worker.runOnce(10);

        assertThat(h.count("lr_card_asset")).isEqualTo(2);
        assertThat(h.count("lr_supplier_command")).isEqualTo(2);
    }

    @Test
    void topUpAddsOnePlanAndAddPlanCommandOnTheChosenCard() {
        h.acceptShop("O-5", "OI-5", c.skuANew, 2, "m1");
        worker.runOnce(10);
        List<Long> cardIds = h.jdbc.queryForList("SELECT id FROM lr_card_asset ORDER BY unit_seq", Long.class);
        h.jdbc.update("UPDATE lr_card_asset SET status = 'DELIVERED'");

        long line = h.acceptTopUp("O-6", "OI-6", c.skuATopUp, "m1", cardIds.getFirst());
        worker.runOnce(10);
        worker.runOnce(10);

        assertThat(h.count("lr_card_asset")).as("加包不建新卡").isEqualTo(2);
        assertThat(h.jdbc.queryForObject("SELECT card_asset_id FROM lr_card_plan WHERE fulfillment_line_id = ?", Long.class, line))
                .isEqualTo(cardIds.getFirst());
        assertThat(h.jdbc.queryForMap("SELECT kind, status FROM lr_card_plan WHERE fulfillment_line_id = ?", line))
                .containsEntry("kind", "TOP_UP").containsEntry("status", "PENDING");
        assertThat(h.jdbc.queryForObject("SELECT type FROM lr_supplier_command WHERE card_plan_id = (SELECT id FROM lr_card_plan WHERE fulfillment_line_id = ?)",
                String.class, line)).isEqualTo("ADD_PLAN");
        assertThat(h.count("lr_card_plan WHERE card_asset_id = ?", cardIds.get(1))).as("同单其它卡不受影响").isEqualTo(1);
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void lineThatCannotBeExpandedIsRetriedThenEscalatedOnce() {
        h.jdbc.update("INSERT INTO lr_fulfillment_line (source_type, source_ref, source_line_ref, qty, sku_binding_id, owner_member_id, status) "
                + "VALUES ('SHOP', 'O-7', 'OI-7', 1, 999999, 'm1', 'RECEIVED')");
        h.acceptShop("O-8", "OI-8", c.skuBNew, 1, "m1");

        for (int i = 0; i < FulfillmentWorker.MAX_EXPAND_ATTEMPTS + 2; i++) {
            worker.runOnce(10);
        }

        assertThat(h.jdbc.queryForMap("SELECT status, attempts, last_error FROM lr_fulfillment_line WHERE source_line_ref = 'OI-7'"))
                .containsEntry("status", "RECEIVED").containsEntry("attempts", FulfillmentWorker.MAX_EXPAND_ATTEMPTS);
        assertThat(h.count("lr_manual_task WHERE kind = 'EXPAND_FAILED' AND status = 'OPEN'")).isEqualTo(1);
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_fulfillment_line WHERE source_line_ref = 'OI-8'", String.class))
                .as("坏行不挡其它行").isEqualTo("IN_PROGRESS");
    }
}
