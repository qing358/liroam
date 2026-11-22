package com.liroam.fulfillment.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveCipher.Sealed;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.pool.CardPool.ImportReport;
import com.liroam.fulfillment.pool.CardPool.PoolItemRow;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.PoolItems.Item;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.fulfillment.worker.FulfillmentWorker;

/** 卡池：导入加密 + 按卡号哈希去重 + 交码密文；分配 SKIP LOCKED 互不阻塞。 */
class CardPoolTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static CardPool pool;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_pool");
        c = h.catalog;
        pool = h.bean(CardPool.class);
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
    void importEncryptsDedupesByHashAndStoresLpaCipherOnlyWhenGiven() {
        String first = h.upstreamA.idleIccid();
        String second = h.upstreamA.idleIccid();
        pool.importItems(c.supplierA, c.productA, null, List.of(new PoolItemRow(first, null)), "op1");

        ImportReport report = pool.importItems(c.supplierA, c.productA, null, List.of(
                new PoolItemRow(first.toLowerCase(), "LPA:1$dup$"),
                new PoolItemRow(second.substring(0, 5) + " " + second.substring(5), "LPA:1$pool.example$X"),
                new PoolItemRow(second, null),
                new PoolItemRow("bad*iccid", null)), "op1");

        assertThat(report.imported()).isEqualTo(1);
        assertThat(report.duplicates()).as("已在池内 + 同批重复").isEqualTo(2);
        assertThat(report.invalid()).extracting(CardPool.Problem::lineNo).containsExactly(4);
        List<Map<String, Object>> rows = h.jdbc.queryForList(
                "SELECT iccid_cipher, iccid_hash, iccid_last4, credential_cipher, key_version, status, form FROM lr_card_pool_item ORDER BY id");
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(new String((byte[]) r.get("iccid_cipher"))).doesNotContain("89852");
            assertThat((byte[]) r.get("iccid_hash")).hasSize(32);
            assertThat(r).containsEntry("status", "IN_STOCK").containsEntry("form", "ESIM");
        });
        assertThat(rows.get(0).get("credential_cipher")).isNull();
        SensitiveCipher cipher = h.bean(SensitiveCipher.class);
        Sealed lpa = new Sealed(((Number) rows.get(1).get("key_version")).intValue(), (byte[]) rows.get(1).get("credential_cipher"));
        assertThat(cipher.open(SensitiveField.CREDENTIAL, lpa)).isEqualTo("LPA:1$pool.example$X");
        assertThat(rows.get(1).get("iccid_last4")).isEqualTo(second.substring(second.length() - 4));
        assertThat(h.count("lr_audit_log WHERE action = 'POOL_IMPORT' AND actor = 'op1'")).isEqualTo(2);
    }

    @Test
    void iccidAlreadyOnACardCountsAsDuplicate() {
        long line = h.acceptShop("O-P", "O-P-1", c.skuBNew, 1, "m1");
        h.drain();
        Map<String, Object> card = h.jdbc.queryForMap("SELECT iccid_cipher, key_version FROM lr_card_asset WHERE fulfillment_line_id = ?", line);
        String iccid = h.bean(SensitiveCipher.class).open(SensitiveField.ICCID,
                new Sealed(((Number) card.get("key_version")).intValue(), (byte[]) card.get("iccid_cipher")));

        ImportReport report = pool.importItems(c.supplierA, c.productA, null, List.of(new PoolItemRow(iccid, null)), "op1");

        assertThat(report.duplicates()).isEqualTo(1);
        assertThat(h.count("lr_card_pool_item")).isZero();
    }

    @Test
    void productMustBelongToSupplier() {
        assertThatThrownBy(() -> pool.importItems(c.supplierA, c.productB, null, List.of(), "op1")).isInstanceOf(UserFacingException.class);
    }

    @Test
    void physicalStockIsSeparateFromEsimStockAndFormMustBeAllowed() {
        pool.importItems(c.supplierA, c.productA, "physical", List.of(new PoolItemRow(h.upstreamA.idleIccid(), null)), "op1");

        assertThat(h.jdbc.queryForList("SELECT form FROM lr_card_pool_item", String.class)).containsExactly("PHYSICAL");
        h.acceptCard("O-P3", c.skuANew, "m1");
        assertThat(h.count("lr_card_pool_item WHERE status = 'IN_STOCK'")).as("eSIM 单不占实体卡库存").isEqualTo(1);
        assertThatThrownBy(() -> pool.importItems(c.supplierA, c.productA, "SOFT", List.of(), "op1")).isInstanceOf(UserFacingException.class)
                .hasMessage("该卡产品不支持这种卡形态");
    }

    @Test
    void concurrentAllocationsSkipEachOthersLockedItemInsteadOfWaiting() throws Exception {
        h.importPoolA(2, false);
        h.acceptShop("O-P2", "O-P2-1", c.skuANew, 2, "m1");
        h.bean(FulfillmentWorker.class).runOnce(10);
        List<Long> cards = h.jdbc.queryForList("SELECT id FROM lr_card_asset ORDER BY unit_seq", Long.class);
        CardAssets assets = h.bean(CardAssets.class);
        TransactionTemplate tx = h.bean(TransactionTemplate.class);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        Optional<Item> got;
        Optional<Item> held;
        try {
            Future<Optional<Item>> holder = threads.submit(() -> tx.execute(s -> {
                Optional<Item> item = pool.allocate(assets.find(cards.get(0)).orElseThrow());
                locked.countDown();
                await(release);
                return item;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Optional<Item>> second = threads.submit(() -> tx.execute(s -> pool.allocate(assets.find(cards.get(1)).orElseThrow())));
            try {
                got = second.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            held = holder.get(10, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }

        assertThat(got).isPresent();
        assertThat(held).isPresent();
        assertThat(got.get().id()).isNotEqualTo(held.get().id());
        assertThat(h.count("lr_card_pool_item WHERE status = 'ALLOCATED'")).isEqualTo(2);
    }

    @Test
    void csvParsingSkipsHeaderAndBlankLinesAndKeepsOptionalLpa() {
        List<PoolItemRow> rows = CardPool.parseCsv(new StringReader("iccid,lpa\n8985200000000000001F,LPA:1$a$b\n\n8985200000000000002F\n"));

        assertThat(rows).extracting(PoolItemRow::iccid).containsExactly("8985200000000000001F", "8985200000000000002F");
        assertThat(rows).extracting(PoolItemRow::lpa).containsExactly("LPA:1$a$b", null);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
