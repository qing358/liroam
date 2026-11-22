package com.liroam.fulfillment.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveCipher.Sealed;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.CredentialDelivered;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.credential.CredentialImports.ImportReport;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.RecordingCredentialEvents;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.fulfillment.worker.CommandExecutor;
import com.liroam.fulfillment.worker.FulfillmentWorker;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;
import com.liroam.supplier.spi.contract.UpstreamFixture.Failure;

/** 交码三通道（CT-FUL-06）、实名门、履约行 PARTIAL/COMPLETED、整单回写壳发货（V5）、开通超时转人工。 */
class CredentialDeliveryTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static CredentialEntry entry;
    private static CredentialImports imports;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_credential");
        c = h.catalog;
        entry = h.bean(CredentialEntry.class);
        imports = h.bean(CredentialImports.class);
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    private List<CredentialDelivered> events() {
        return h.bean(RecordingCredentialEvents.class).events();
    }

    private String openCredential(long card) {
        Map<String, Object> row = h.jdbc.queryForMap(
                "SELECT payload_cipher, key_version FROM lr_card_credential WHERE card_asset_id = ? AND superseded_by IS NULL", card);
        return h.bean(SensitiveCipher.class).open(SensitiveField.CREDENTIAL,
                new Sealed(((Number) row.get("key_version")).intValue(), (byte[]) row.get("payload_cipher")));
    }

    @Test
    void ct_ful_06_allThreeChannelsWriteCredentialPublishEventAndDeliverCard() {
        long apiLine = h.acceptShop("O-1", "O-1-1", c.skuBNew, 1, "m1");
        List<String> iccids = h.importPoolA(2, false);
        long importLine = h.acceptShop("O-2", "O-2-1", c.skuANew, 1, "m1");
        long manualLine = h.acceptShop("O-3", "O-3-1", c.skuANew, 1, "m1");
        h.drain();
        long apiCard = h.cardOfLine(apiLine);
        long importCard = h.cardOfLine(importLine);
        long manualCard = h.cardOfLine(manualLine);
        String importIccid = h.jdbc.queryForObject("SELECT iccid_last4 FROM lr_card_asset WHERE id = ?", String.class, importCard);
        String csvIccid = iccids.stream().filter(i -> i.endsWith(importIccid)).findFirst().orElseThrow();

        ImportReport report = imports.importCsv(new StringReader("iccid,lpa\n" + csvIccid + ",LPA:1$import$1\n"), "op1");
        entry.enter(manualCard, " LPA:1$manual$1 ", "op2");

        assertThat(report.delivered()).isEqualTo(1);
        assertThat(events()).containsExactly(new CredentialDelivered(apiCard, CredentialChannel.API),
                new CredentialDelivered(importCard, CredentialChannel.IMPORT), new CredentialDelivered(manualCard, CredentialChannel.MANUAL));
        assertThat(List.of(apiCard, importCard, manualCard)).allSatisfy(card -> assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED"));
        assertThat(h.jdbc.queryForMap("SELECT channel, operator_id FROM lr_card_credential WHERE card_asset_id = ?", manualCard))
                .containsEntry("channel", "MANUAL").containsEntry("operator_id", "op2");
        assertThat(openCredential(importCard)).isEqualTo("LPA:1$import$1");
        assertThat(openCredential(manualCard)).isEqualTo("LPA:1$manual$1");
        assertThat(List.of(apiLine, importLine, manualLine)).allSatisfy(l -> assertThat(h.status("lr_fulfillment_line", l)).isEqualTo("COMPLETED"));
    }

    @Test
    void reEnteringReplacesCurrentCredentialWithoutMovingCardAgain() {
        h.importPoolA(1, true);
        long card = h.acceptCard("O-4", c.skuANew, "m1");
        h.drain();

        entry.enter(card, "LPA:1$new$", "op1");

        assertThat(h.count("lr_card_credential WHERE card_asset_id = ?", card)).isEqualTo(2);
        assertThat(h.count("lr_card_credential WHERE card_asset_id = ? AND superseded_by IS NULL", card)).isEqualTo(1);
        assertThat(openCredential(card)).isEqualTo("LPA:1$new$");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
    }

    @Test
    void manualEntryRejectsBlankAndCardsThatNeverProvisioned() {
        long card = h.acceptCard("O-5", c.skuBNew, "m1");
        h.bean(FulfillmentWorker.class).runOnce(10);

        assertThatThrownBy(() -> entry.enter(card, " ", "op1")).isInstanceOf(UserFacingException.class).hasMessage("请填写激活码");
        assertThatThrownBy(() -> entry.enter(card, "LPA:1$x$", "op1")).isInstanceOf(UserFacingException.class)
                .hasMessage("这张卡当前状态不能录入激活码");
        assertThatThrownBy(() -> entry.enter(999_999, "LPA:1$x$", "op1")).isInstanceOf(UserFacingException.class).hasMessage("找不到这张卡");
        assertThat(h.count("lr_card_credential")).isZero();
    }

    @Test
    void importReportsUnmatchedRowsAndPreloadsUnallocatedPoolItems() {
        List<String> iccids = h.importPoolA(1, false);
        String unknown = h.upstreamA.idleIccid();

        ImportReport report = imports.importCsv(new StringReader(String.join("\n", "iccid,lpa", iccids.getFirst() + ",LPA:1$pre$",
                unknown + ",LPA:1$x$", "not*valid,LPA:1$y$", unknown)), "op1");

        assertThat(report.pooled()).isEqualTo(1);
        assertThat(report.delivered()).isZero();
        assertThat(report.unmatched()).extracting(CredentialImports.Unmatched::lineNo, CredentialImports.Unmatched::reason)
                .containsExactly(tuple(2, "找不到这张卡"), tuple(3, "卡号格式不对"),
                        tuple(4, "缺激活码"));
        assertThat(report.unmatched().getFirst().iccidLast4()).isEqualTo(unknown.substring(unknown.length() - 4));

        long card = h.acceptCard("O-6", c.skuANew, "m1");
        h.drain();
        assertThat(h.status("lr_card_asset", card)).as("预置的交码在开通后自动交付").isEqualTo("DELIVERED");
        assertThat(openCredential(card)).isEqualTo("LPA:1$pre$");
    }

    @Test
    void importBeforeProvisionIsHeldUntilCardIsProvisioned() {
        List<String> iccids = h.importPoolA(1, false);
        long card = h.acceptCard("O-7", c.skuANew, "m1");
        h.bean(FulfillmentWorker.class).runOnce(10);
        h.bean(CommandExecutor.class).runOnce(10, "w1");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("ALLOCATED");

        assertThat(imports.importCsv(new StringReader(iccids.getFirst() + ",LPA:1$early$"), "op1").delivered()).isEqualTo(1);
        assertThat(h.status("lr_card_asset", card)).isEqualTo("ALLOCATED");
        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
    }

    @Test
    void kycBlockDeliveryKeepsCardProvisionedWithCredentialStored() {
        h.jdbc.update("UPDATE lr_sku_binding SET kyc_gate_mode = 'BLOCK_DELIVERY' WHERE id = ?", c.skuBNew);
        long line = h.acceptShop("O-8", "O-8-1", c.skuBNew, 1, "m1");

        h.drain();
        h.clock.advance(Duration.ofHours(3));
        h.bean(FulfillmentWorker.class).runOnce(10);

        long card = h.cardOfLine(line);
        assertThat(h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");
        assertThat(h.count("lr_card_credential WHERE card_asset_id = ?", card)).isEqualTo(1);
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("IN_PROGRESS");
        assertThat(h.count("lr_manual_task WHERE kind = 'CREDENTIAL_TIMEOUT'")).as("已有交码不算超时未交码").isZero();
    }

    @Test
    void exceptionMakesLinePartialAndLaterManualDeliveryCompletesItAndTheOrder() {
        long line = h.acceptShop("O-9", "O-9-1", c.skuBNew, 2, "m1");
        h.bean(FulfillmentWorker.class).runOnce(10);
        h.bean(CommandExecutor.class).runOnce(10, "w1");
        h.upstreamB.failNext(Failure.BAD_REQUEST);
        h.drain();
        List<Long> cards = h.jdbc.queryForList("SELECT id FROM lr_card_asset WHERE fulfillment_line_id = ? ORDER BY unit_seq", Long.class, line);
        assertThat(h.status("lr_card_asset", cards.get(0))).isEqualTo("EXCEPTION");
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("PARTIAL");
        h.bean(FulfillmentWorker.class).runOnce(10);
        assertThat(h.shell.calls()).isEmpty();

        entry.enter(cards.get(0), "LPA:1$fix$", "op1");
        h.bean(FulfillmentWorker.class).runOnce(10);

        assertThat(h.status("lr_card_asset", cards.get(0))).isEqualTo("DELIVERED");
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("COMPLETED");
        assertThat(h.shell.calls()).containsExactly("O-9");
    }

    @Test
    void v5_shellDeliveryIsMarkedExactlyOnceWhenTheWholeOrderCompletes() {
        h.importPoolA(1, false);
        long lineB = h.acceptShop("O-10", "O-10-1", c.skuBNew, 1, "m1");
        long lineA = h.acceptShop("O-10", "O-10-2", c.skuANew, 1, "m1");
        h.drain();
        assertThat(h.status("lr_fulfillment_line", lineB)).isEqualTo("COMPLETED");
        assertThat(h.status("lr_fulfillment_line", lineA)).isEqualTo("IN_PROGRESS");
        assertThat(h.shell.calls()).as("整单未完成不回写").isEmpty();

        entry.enter(h.cardOfLine(lineA), "LPA:1$a$", "op1");
        FulfillmentWorker worker = h.bean(FulfillmentWorker.class);
        worker.runOnce(10);
        worker.runOnce(10);
        entry.enter(h.cardOfLine(lineA), "LPA:1$again$", "op1");
        h.clock.advance(Duration.ofHours(1));
        h.drain();

        assertThat(h.shell.calls()).containsExactly("O-10");
        assertThat(h.jdbc.queryForMap("SELECT status, attempts FROM lr_shell_delivery WHERE li_order_sn = 'O-10'"))
                .containsEntry("status", "DONE").containsEntry("attempts", 1);
    }

    @Test
    void failedShellDeliveryIsRetriedAfterBackoffUntilItSucceeds() {
        h.importPoolA(1, true);
        h.acceptShop("O-12", "O-12-1", c.skuANew, 1, "m1");
        h.shell.failNext(1);
        h.drain();
        assertThat(h.shell.calls()).containsExactly("O-12");
        assertThat(h.jdbc.queryForMap("SELECT status, attempts, last_error IS NOT NULL AS has_error FROM lr_shell_delivery WHERE li_order_sn = 'O-12'"))
                .containsEntry("status", "PENDING").containsEntry("attempts", 1).containsEntry("has_error", 1L);

        FulfillmentWorker worker = h.bean(FulfillmentWorker.class);
        worker.runOnce(10);
        assertThat(h.shell.calls()).as("退避未到不重试").hasSize(1);
        h.clock.advance(Duration.ofSeconds(30));
        worker.runOnce(10);
        worker.runOnce(10);

        assertThat(h.shell.calls()).containsExactly("O-12", "O-12");
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_shell_delivery WHERE li_order_sn = 'O-12'", String.class)).isEqualTo("DONE");
    }

    @Test
    void provisionedCardWithoutCredentialPastSupplierTimeoutOpensOneTask() {
        h.bean(Settings.class).set(FulfillmentSettings.CREDENTIAL_TIMEOUT, SettingScope.supplier(c.supplierA), Duration.ofMinutes(30), "op");
        h.importPoolA(1, false);
        long card = h.acceptCard("O-11", c.skuANew, "m1");
        h.drain();
        FulfillmentWorker worker = h.bean(FulfillmentWorker.class);

        h.clock.advance(Duration.ofMinutes(29));
        worker.runOnce(10);
        assertThat(h.count("lr_manual_task WHERE kind = 'CREDENTIAL_TIMEOUT'")).isZero();
        h.clock.advance(Duration.ofMinutes(2));
        worker.runOnce(10);
        worker.runOnce(10);

        assertThat(h.count("lr_manual_task WHERE kind = 'CREDENTIAL_TIMEOUT' AND ref_id = ?", String.valueOf(card))).isEqualTo(1);
    }
}
