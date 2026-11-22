package com.liroam.fulfillment.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.store.SupplierCommands;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.supplier.spi.ProvisionCommand;
import com.liroam.supplier.spi.contract.UpstreamFixture.Failure;

/** 指令执行 §6.3：认领租约与崩溃回收（CT-FUL-03）、四态结果（V10）、退避与转人工、开通成功推进、冻结推迟。 */
class CommandExecutorTest {

    private static FulfillmentHarness h;
    private static TestCatalog c;
    private static FulfillmentWorker worker;
    private static CommandExecutor executor;

    @BeforeAll
    static void start() {
        h = FulfillmentHarness.start("fulfillment_executor");
        c = h.catalog;
        worker = h.bean(FulfillmentWorker.class);
        executor = h.bean(CommandExecutor.class);
    }

    @AfterAll
    static void stop() {
        h.close();
    }

    @BeforeEach
    void reset() {
        h.reset();
    }

    private long acceptAndExpand(String order, long sku) {
        long line = h.acceptShop(order, order + "-1", sku, 1, "m1");
        worker.runOnce(10);
        return h.cardOfLine(line);
    }

    @Test
    void createOnOrderSupplierProvisionsThenFetchesCredentialViaApiAndDeliversCard() {
        long line = h.acceptShop("O-B", "O-B-1", c.skuBNew, 1, "m1");

        h.drain();

        long card = h.cardOfLine(line);
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        assertThat(h.jdbc.queryForMap("SELECT iccid_last4, upstream_ref, iccid_cipher IS NOT NULL AS sealed, iccid_hash IS NOT NULL AS hashed, "
                + "provisioned_at IS NOT NULL AS provisioned FROM lr_card_asset WHERE id = ?", card))
                .containsEntry("sealed", 1L).containsEntry("hashed", 1L).containsEntry("provisioned", 1L)
                .satisfies(m -> assertThat((String) m.get("upstream_ref")).startsWith("SUB-"))
                .satisfies(m -> assertThat((String) m.get("iccid_last4")).hasSize(4));
        assertThat(h.jdbc.queryForObject("SELECT status FROM lr_card_plan WHERE card_asset_id = ?", String.class, card)).isEqualTo("READY");
        assertThat(h.jdbc.queryForList("SELECT CONCAT(type, ':', status) FROM lr_supplier_command WHERE card_asset_id = ? ORDER BY id", String.class, card))
                .containsExactly("PROVISION:SUCCEEDED", "FETCH_CREDENTIAL:SUCCEEDED");
        assertThat(h.jdbc.queryForMap("SELECT channel, kind, operator_id, payload_cipher FROM lr_card_credential WHERE card_asset_id = ?", card))
                .containsEntry("channel", "API").containsEntry("kind", "LPA").containsEntry("operator_id", "system:api")
                .satisfies(m -> assertThat(new String((byte[]) m.get("payload_cipher"))).doesNotContain("LPA:1"));
        assertThat(h.jdbc.queryForList("SELECT CONCAT(operation, ':', outcome) FROM lr_supplier_call_log ORDER BY id", String.class))
                .containsExactly("PROVISION:OK", "FETCH_CREDENTIAL:OK");
        assertThat(h.jdbc.queryForList("SELECT CONCAT(request_redacted, response_redacted) FROM lr_supplier_call_log", String.class))
                .as("调用日志不落 ICCID 全号与交码").noneMatch(s -> s.matches(".*\\d{19}.*") || s.contains("LPA:1"));
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("COMPLETED");
        assertThat(h.jdbc.queryForObject("SELECT poll_tier FROM lr_card_asset WHERE id = ?", String.class, card)).isEqualTo("NOT_ACTIVATED");
    }

    @Test
    void poolSupplierAllocatesLocallyThenProvisionsAndDeliversBatchCredentialAsImport() {
        List<String> iccids = h.importPoolA(1, true);
        long line = h.acceptShop("O-A", "O-A-1", c.skuANew, 1, "m1");

        h.drain();

        long card = h.cardOfLine(line);
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        assertThat(h.jdbc.queryForObject("SELECT iccid_last4 FROM lr_card_asset WHERE id = ?", String.class, card))
                .isEqualTo(iccids.getFirst().substring(iccids.getFirst().length() - 4));
        assertThat(h.jdbc.queryForMap("SELECT status, card_asset_id FROM lr_card_pool_item"))
                .containsEntry("status", "ALLOCATED").satisfies(m -> assertThat(((Number) m.get("card_asset_id")).longValue()).isEqualTo(card));
        assertThat(h.jdbc.queryForObject("SELECT channel FROM lr_card_credential WHERE card_asset_id = ?", String.class, card)).isEqualTo("IMPORT");
        assertThat(h.jdbc.queryForList("SELECT operation FROM lr_supplier_call_log", String.class)).as("本地分配不调上游").containsExactly("PROVISION");
        assertThat(h.upstreamA.effectiveProvisions(h.command("PROVISION", card).get("request_no").toString())).isEqualTo(1);
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("COMPLETED");
    }

    @Test
    void poolCardWithoutCredentialWaitsProvisionedWithoutApiFetch() {
        h.importPoolA(1, false);
        long card = acceptAndExpand("O-A2", c.skuANew);

        h.drain();

        assertThat(h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");
        assertThat(h.count("lr_supplier_command WHERE type = 'FETCH_CREDENTIAL'")).as("该供应商无 API 交码通道").isZero();
        assertThat(h.count("lr_card_credential")).isZero();
    }

    @Test
    void emptyPoolPostponesWithoutCountingAndOpensOneTaskThenAllocatesAfterImport() {
        long card = acceptAndExpand("O-A3", c.skuANew);
        for (int i = 0; i < 4; i++) {
            executor.runOnce(10, "w1");
            h.clock.advance(Duration.ofMinutes(10));
        }

        assertThat(h.command("ALLOCATE", card)).containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("error_code", "POOL_EMPTY");
        assertThat(h.count("lr_manual_task WHERE kind = 'POOL_EMPTY' AND ref_type = 'CARD_PRODUCT' AND ref_id = ?", String.valueOf(c.productA)))
                .as("池空只开一条待办，不刷屏").isEqualTo(1);

        h.importPoolA(1, true);
        h.drain();
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
    }

    @Test
    void ct_ful_03_crashAfterSendIsReclaimedAfterLeaseAndResolvedByLookupWithoutSecondProvision() {
        String iccid = h.importPoolA(1, false).getFirst();
        long card = acceptAndExpand("O-C", c.skuANew);
        executor.runOnce(10, "w1");
        SupplierCommands commands = h.bean(SupplierCommands.class);
        Instant now = h.clock.instant();
        var claimed = commands.claim(10, "crashed", now, now.plus(CommandExecutor.LEASE));
        assertThat(claimed).hasSize(1).first().satisfies(cmd -> assertThat(cmd.type().name()).isEqualTo("PROVISION"));
        String requestNo = claimed.getFirst().requestNo();
        h.adapterA.provision(new ProvisionCommand(requestNo, card, iccid, "BASE-1", Map.of()));

        assertThat(executor.runOnce(10, "w2")).as("租约未过期，别的 worker 认领不到").isZero();
        h.clock.advance(CommandExecutor.LEASE.plusSeconds(1));
        assertThat(executor.runOnce(10, "w2")).isEqualTo(1);

        assertThat(h.upstreamA.effectiveProvisions(requestNo)).as("查档确认已生效，不重复开卡").isEqualTo(1);
        assertThat(h.command("PROVISION", card)).containsEntry("status", "SUCCEEDED");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");
        assertThat(h.jdbc.queryForList("SELECT CONCAT(operation, ':', outcome) FROM lr_supplier_call_log", String.class))
                .containsExactly("LOOKUP_PROVISION:OK");
        assertThat(commands.stillHeld(claimed.getFirst().id(), "crashed")).as("崩溃者的结果之后不会再落库").isFalse();
    }

    @Test
    void ct_ful_03_crashBeforeSendIsReclaimedLookedUpAsNotEffectiveAndSentOnce() {
        h.importPoolA(1, false);
        long card = acceptAndExpand("O-C2", c.skuANew);
        executor.runOnce(10, "w1");
        SupplierCommands commands = h.bean(SupplierCommands.class);
        Instant now = h.clock.instant();
        String requestNo = commands.claim(10, "crashed", now, now.plus(CommandExecutor.LEASE)).getFirst().requestNo();

        h.clock.advance(CommandExecutor.LEASE.plusSeconds(1));
        h.drain();

        assertThat(h.upstreamA.effectiveProvisions(requestNo)).isEqualTo(1);
        assertThat(h.jdbc.queryForList("SELECT CONCAT(operation, ':', outcome) FROM lr_supplier_call_log ORDER BY id", String.class))
                .containsExactly("LOOKUP_PROVISION:OK", "PROVISION:OK");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");
    }

    @Test
    void v10_upstreamThrottleIsNotAnAttemptAndWriteTimeoutIsResentWithSameRequestNoWhenSupplierHasIdempotencyKey() {
        long card = acceptAndExpand("O-T", c.skuBNew);

        h.upstreamB.failNext(Failure.THROTTLED);
        executor.runOnce(10, "w1");
        Map<String, Object> throttled = h.command("PROVISION", card);
        assertThat(throttled).containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("error_class", "THROTTLED");
        assertThat(UtcColumns.fromColumn((LocalDateTime) throttled.get("next_attempt_at"))).isEqualTo(h.clock.instant().plusSeconds(1));

        h.clock.advance(Duration.ofSeconds(1));
        h.upstreamB.failNext(Failure.TIMEOUT_AFTER_SEND);
        executor.runOnce(10, "w1");
        assertThat(h.command("PROVISION", card)).containsEntry("status", "LOOKUP_PENDING").containsEntry("attempts", 1);

        h.clock.advance(Duration.ofMinutes(5));
        h.drain();
        String requestNo = h.command("PROVISION", card).get("request_no").toString();
        assertThat(h.upstreamB.effectiveProvisions(requestNo)).isEqualTo(1);
        assertThat(h.command("PROVISION", card)).containsEntry("status", "SUCCEEDED");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("DELIVERED");
        assertThat(h.jdbc.queryForList("SELECT CONCAT(operation, ':', outcome) FROM lr_supplier_call_log ORDER BY id", String.class))
                .containsExactly("PROVISION:THROTTLED", "PROVISION:UNKNOWN", "PROVISION:OK", "FETCH_CREDENTIAL:OK");
    }

    @Test
    void v10_writeTimeoutWithoutIdempotencyKeyGoesToLookupAndSucceedsWithoutSecondProvision() {
        h.importPoolA(1, false);
        long card = acceptAndExpand("O-T2", c.skuANew);
        executor.runOnce(10, "w1");

        h.upstreamA.failNext(Failure.TIMEOUT_AFTER_SEND);
        executor.runOnce(10, "w1");
        assertThat(h.command("PROVISION", card)).containsEntry("status", "LOOKUP_PENDING");
        h.clock.advance(Duration.ofMinutes(5));
        h.drain();

        String requestNo = h.command("PROVISION", card).get("request_no").toString();
        assertThat(h.upstreamA.effectiveProvisions(requestNo)).isEqualTo(1);
        assertThat(h.status("lr_card_asset", card)).isEqualTo("PROVISIONED");
        assertThat(h.jdbc.queryForList("SELECT CONCAT(operation, ':', outcome) FROM lr_supplier_call_log ORDER BY id", String.class))
                .containsExactly("PROVISION:UNKNOWN", "LOOKUP_PROVISION:OK");
    }

    @Test
    void v10_rejectedGoesManualWithTaskAndMarksCardExceptionAndLinePartial() {
        long line = h.acceptShop("O-R", "O-R-1", c.skuBNew, 2, "m1");
        worker.runOnce(10);
        h.upstreamB.failNext(Failure.BAD_REQUEST);

        h.drain();

        List<Map<String, Object>> cards = h.jdbc.queryForList("SELECT id, status, exception_from FROM lr_card_asset ORDER BY unit_seq");
        assertThat(cards).extracting(m -> m.get("status")).containsExactly("EXCEPTION", "DELIVERED");
        assertThat(cards.getFirst()).containsEntry("exception_from", "CREATED");
        long bad = ((Number) cards.getFirst().get("id")).longValue();
        assertThat(h.command("PROVISION", bad)).containsEntry("status", "MANUAL").containsEntry("error_class", "REJECTED").containsEntry("attempts", 1);
        assertThat(h.count("lr_manual_task WHERE kind = 'COMMAND_FAILED' AND status = 'OPEN'")).isEqualTo(1);
        assertThat(h.status("lr_fulfillment_line", line)).isEqualTo("PARTIAL");
        assertThat(h.shell.calls()).isEmpty();
    }

    @Test
    void serverErrorsBackOffExponentiallyPerSupplierSettingsThenGoManual() {
        h.bean(Settings.class).set(FulfillmentSettings.COMMAND_BACKOFF_BASE_S, SettingScope.supplier(c.supplierB), 10, "op");
        h.bean(Settings.class).set(FulfillmentSettings.COMMAND_MAX_ATTEMPTS, SettingScope.supplier(c.supplierB), 3, "op");
        long card = acceptAndExpand("O-E", c.skuBNew);

        h.upstreamB.failNext(Failure.SERVER_ERROR);
        executor.runOnce(10, "w1");
        assertNextAttemptIn(card, Duration.ofSeconds(10), 1);
        h.clock.advance(Duration.ofSeconds(10));
        h.upstreamB.failNext(Failure.CONNECT_FAILED);
        executor.runOnce(10, "w1");
        assertNextAttemptIn(card, Duration.ofSeconds(20), 2);
        h.clock.advance(Duration.ofSeconds(20));
        h.upstreamB.failNext(Failure.SERVER_ERROR);
        executor.runOnce(10, "w1");

        assertThat(h.command("PROVISION", card)).containsEntry("status", "MANUAL").containsEntry("attempts", 3).containsEntry("error_class", "RETRYABLE");
        assertThat(h.status("lr_card_asset", card)).isEqualTo("EXCEPTION");
        assertThat(h.count("lr_manual_task WHERE kind = 'COMMAND_FAILED'")).isEqualTo(1);
    }

    @Test
    void unknownOnReadIsACountedRetryNotALookup() {
        long card = acceptAndExpand("O-U", c.skuBNew);
        executor.runOnce(10, "w1");

        h.upstreamB.failNext(Failure.TIMEOUT_AFTER_SEND);
        executor.runOnce(10, "w1");

        assertThat(h.command("FETCH_CREDENTIAL", card)).containsEntry("status", "PENDING").containsEntry("attempts", 1).containsEntry("error_class", "UNKNOWN");
    }

    @Test
    void provisioningCommandsWaitWhileCardIsHeldForRefund() {
        long card = acceptAndExpand("O-H", c.skuBNew);
        h.jdbc.update("UPDATE lr_card_asset SET refund_hold = 1 WHERE id = ?", card);

        executor.runOnce(10, "w1");

        assertThat(h.command("PROVISION", card)).containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("error_code", "REFUND_HOLD");
        assertThat(h.count("lr_supplier_call_log")).isZero();
    }

    private void assertNextAttemptIn(long card, Duration delay, int attempts) {
        Map<String, Object> cmd = h.command("PROVISION", card);
        assertThat(cmd).containsEntry("status", "PENDING").containsEntry("attempts", attempts);
        assertThat(UtcColumns.fromColumn((LocalDateTime) cmd.get("next_attempt_at"))).isEqualTo(h.clock.instant().plus(delay));
    }
}
