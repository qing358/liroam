package com.liroam.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.fulfillment.FulfillmentRejected.Reason;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.FulfillmentLines;
import com.liroam.schema.testing.ItSchema;

/** 唯一履约入口：幂等（CT-FUL-01）、加包四拒（CT-FUL-02）及其余校验分支、参与调用方事务。 */
class FulfillmentEntryTest {

    private static final long SUP_A = 1;
    private static final long SUP_B = 2;
    private static final long SUP_UNLISTED = 3;

    private static final long SKU_NEW_LISTED = 11;
    private static final long SKU_NEW_DRAFT = 12;
    private static final long SKU_TOPUP_A = 13;
    private static final long SKU_NEW_UNLISTED_SUPPLIER = 14;

    private static final long CARD_M1_A = 101;
    private static final long CARD_M2_A = 102;
    private static final long CARD_M1_B = 103;
    private static final long CARD_M1_A_HOLD = 104;
    private static final long CARD_M1_A_TERMINATED = 105;
    private static final long CARD_M1_A_CANCELLED = 106;
    private static final long CARD_UNOWNED_A = 107;

    private static DataSource ds;
    private static JdbcTemplate jdbc;
    private static FulfillmentEntry entry;

    @BeforeAll
    static void seed() {
        ds = ItSchema.migrated("fulfillment_entry");
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO lr_supplier (id, code, name, adapter_key, enabled, listing_enabled) VALUES (1, 'SUP_A', 'A', 'sup_a', 1, 1), "
                + "(2, 'SUP_B', 'B', 'sup_b', 1, 1), (3, 'SUP_C', 'C', 'sup_c', 1, 0)");
        sku(SKU_NEW_LISTED, "NEW_CARD", SUP_A, "LISTED");
        sku(SKU_NEW_DRAFT, "NEW_CARD", SUP_A, "DRAFT");
        sku(SKU_TOPUP_A, "TOP_UP", SUP_A, "LISTED");
        sku(SKU_NEW_UNLISTED_SUPPLIER, "NEW_CARD", SUP_UNLISTED, "LISTED");
        card(CARD_M1_A, "m1", SUP_A, "DELIVERED", false);
        card(CARD_M2_A, "m2", SUP_A, "IN_USE", false);
        card(CARD_M1_B, "m1", SUP_B, "IN_USE", false);
        card(CARD_M1_A_HOLD, "m1", SUP_A, "IN_USE", true);
        card(CARD_M1_A_TERMINATED, "m1", SUP_A, "TERMINATED", false);
        card(CARD_M1_A_CANCELLED, "m1", SUP_A, "CANCELLED", false);
        card(CARD_UNOWNED_A, null, SUP_A, "PROVISIONED", false);

        JdbcClient client = JdbcClient.create(ds);
        entry = new FulfillmentEntry(new FulfillmentRules(new SkuBindings(client), new SupplierDirectory(client), new CardAssets(client),
                new com.liroam.common.setting.Settings(client, new com.liroam.common.audit.AuditLog(client))),
                new FulfillmentLines(client));
    }

    private static void sku(long id, String kind, long supplierId, String state) {
        jdbc.update("INSERT INTO lr_sku_binding (id, li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, form, "
                + "plan_template_id, offering_id, listing_state) VALUES (?, ?, 'G1', ?, ?, 10, 100, 'ESIM', 1, 1, ?)",
                id, "S" + id, kind, supplierId, state);
    }

    private static void card(long id, String owner, long supplierId, String status, boolean hold) {
        jdbc.update("INSERT INTO lr_card_asset (id, fulfillment_line_id, unit_seq, supplier_id, card_product_id, region_id, form, owner_member_id, "
                + "status, refund_hold) VALUES (?, 9000, ?, ?, 10, 100, 'ESIM', ?, ?, ?)", id, id, supplierId, owner, status, hold);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM lr_fulfillment_line");
        jdbc.update("UPDATE lr_sku_binding SET listing_state = 'LISTED' WHERE id = ?", SKU_NEW_LISTED);
    }

    private static FulfillmentRequest shop(String itemSn, long sku, int qty) {
        return new FulfillmentRequest(SourceType.SHOP, "O-1", itemSn, sku, qty, "m1", null, null);
    }

    private static FulfillmentRequest topUp(String itemSn, String member, Long card, long sku, int qty) {
        return new FulfillmentRequest(SourceType.TOPUP, "O-2", itemSn, sku, qty, member, card, null);
    }

    private static int lines() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM lr_fulfillment_line", Integer.class);
    }

    @Test
    void ct_ful_01_repeatedAcceptYieldsOneLineCarryingQty() {
        List<FulfillmentAck> acks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            acks.add(entry.accept(shop("OI-1", SKU_NEW_LISTED, 3)));
        }

        assertThat(lines()).isEqualTo(1);
        assertThat(acks).extracting(FulfillmentAck::fulfillmentLineId).containsOnly(acks.getFirst().fulfillmentLineId());
        assertThat(acks).filteredOn(FulfillmentAck::created).hasSize(1);
        assertThat(jdbc.queryForMap("SELECT qty, status, owner_member_id, source_ref FROM lr_fulfillment_line"))
                .containsEntry("qty", 3).containsEntry("status", "RECEIVED").containsEntry("owner_member_id", "m1").containsEntry("source_ref", "O-1");
    }

    @Test
    void ct_ful_01_concurrentAcceptsFromSeparateConnectionsYieldOneLine() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<FulfillmentAck>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> entry.accept(shop("OI-RACE", SKU_NEW_LISTED, 2)));
            }
            List<FulfillmentAck> acks = new ArrayList<>();
            for (Future<FulfillmentAck> f : pool.invokeAll(calls)) {
                acks.add(f.get());
            }
            assertThat(lines()).isEqualTo(1);
            assertThat(acks).filteredOn(FulfillmentAck::created).hasSize(1);
            assertThat(acks).extracting(FulfillmentAck::fulfillmentLineId).containsOnly(acks.getFirst().fulfillmentLineId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameItemFromAnotherSourceTypeIsADifferentLine() {
        entry.accept(shop("X-1", SKU_NEW_LISTED, 1));
        entry.accept(new FulfillmentRequest(SourceType.CHANNEL, "CH-9", "X-1", SKU_NEW_LISTED, 1, null, null, 77L));

        assertThat(lines()).isEqualTo(2);
    }

    @Test
    void replayAfterDelistingReturnsTheExistingLine() {
        FulfillmentAck first = entry.accept(shop("OI-2", SKU_NEW_LISTED, 1));
        jdbc.update("UPDATE lr_sku_binding SET listing_state = 'DELISTED' WHERE id = ?", SKU_NEW_LISTED);

        FulfillmentAck replay = entry.accept(shop("OI-2", SKU_NEW_LISTED, 1));

        assertThat(replay).isEqualTo(new FulfillmentAck(first.fulfillmentLineId(), false));
    }

    @Test
    void topUpOnOwnCardIsAccepted() {
        FulfillmentAck ack = entry.accept(topUp("OI-T", "m1", CARD_M1_A, SKU_TOPUP_A, 1));

        assertThat(ack.created()).isTrue();
        assertThat(jdbc.queryForObject("SELECT topup_card_asset_id FROM lr_fulfillment_line WHERE id = ?", Long.class, ack.fulfillmentLineId()))
                .isEqualTo(CARD_M1_A);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "TOPUP_CARD_NOT_OWNED,      m1, 102, 1",
            "TOPUP_CARD_NOT_OWNED,      m1, 107, 1",
            "TOPUP_CARD_OTHER_SUPPLIER, m1, 103, 1",
            "TOPUP_CARD_REFUND_HOLD,    m1, 104, 1",
            "TOPUP_CARD_CLOSED,         m1, 105, 1",
            "TOPUP_CARD_CLOSED,         m1, 106, 1",
            "TOPUP_CARD_NOT_FOUND,      m1, 999, 1",
            "TOPUP_QTY_NOT_ONE,         m1, 101, 2",
    })
    void ct_ful_02_topUpIsRejectedWithoutWritingAnything(Reason reason, String member, long card, int qty) {
        assertThatThrownBy(() -> entry.accept(topUp("OI-R", member, card, SKU_TOPUP_A, qty)))
                .isInstanceOfSatisfying(FulfillmentRejected.class, e -> assertThat(e.reason()).isEqualTo(reason));
        assertThat(lines()).isZero();
    }

    @Test
    void topUpWithoutCardIsRejected() {
        assertThatThrownBy(() -> entry.accept(topUp("OI-R", "m1", null, SKU_TOPUP_A, 1)))
                .isInstanceOfSatisfying(FulfillmentRejected.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOPUP_CARD_REQUIRED));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "SKU_NOT_BOUND,         SHOP,  999, 1",
            "SKU_NOT_LISTED,        SHOP,  12,  1",
            "SUPPLIER_NOT_LISTABLE, SHOP,  14,  1",
            "SKU_KIND_MISMATCH,     SHOP,  13,  1",
            "SKU_KIND_MISMATCH,     TOPUP, 11,  1",
            "INVALID_QTY,           SHOP,  11,  0",
    })
    void skuAndQuantityGates(Reason reason, SourceType type, long sku, int qty) {
        FulfillmentRequest r = new FulfillmentRequest(type, "O-3", "OI-G", sku, qty, "m1", type == SourceType.TOPUP ? CARD_M1_A : null, null);

        assertThatThrownBy(() -> entry.accept(r))
                .isInstanceOfSatisfying(FulfillmentRejected.class, e -> assertThat(e.reason()).isEqualTo(reason));
        assertThat(lines()).isZero();
    }

    @Test
    void lineIsRolledBackWithTheCallersTransaction() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        tx.executeWithoutResult(status -> {
            entry.accept(shop("OI-TX", SKU_NEW_LISTED, 1));
            status.setRollbackOnly();
        });

        assertThat(lines()).isZero();
    }
}
