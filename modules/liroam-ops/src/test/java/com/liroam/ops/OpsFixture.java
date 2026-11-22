package com.liroam.ops;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.ops.config.LiroamOpsAutoConfiguration;
import com.liroam.ops.notify.MemberSms;
import com.liroam.ops.recon.ShellPayments;

/** 运营模块集成测试装配：履约两形状假上游 + 运营模块；壳支付与短信用内存替身。 */
final class OpsFixture implements AutoCloseable {

    static final String OP = "manager:admin";

    static final class FakePayments implements ShellPayments {

        final List<PaidOrder> orders = new ArrayList<>();
        java.time.LocalDate askedDay;

        @Override
        public List<PaidOrder> paidOrders(java.time.LocalDate day) {
            askedDay = day;
            return List.copyOf(orders);
        }
    }

    static final class FakeSms implements MemberSms {

        final List<String> sent = new ArrayList<>();
        volatile boolean succeed = true;

        @Override
        public boolean send(String memberId, String text) {
            sent.add(memberId + ":" + text);
            return succeed;
        }
    }

    final FulfillmentHarness h;
    final FakePayments payments;
    final FakeSms sms;

    private OpsFixture(FulfillmentHarness h, FakePayments payments, FakeSms sms) {
        this.h = h;
        this.payments = payments;
        this.sms = sms;
    }

    static OpsFixture start(String module) {
        FakePayments payments = new FakePayments();
        FakeSms sms = new FakeSms();
        FulfillmentHarness h = FulfillmentHarness.start(module, Map.of(), context -> {
            context.registerBean(ShellPayments.class, () -> payments);
            context.registerBean(MemberSms.class, () -> sms);
            context.register(LiroamOpsAutoConfiguration.class);
        });
        return new OpsFixture(h, payments, sms);
    }

    <T> T bean(Class<T> type) {
        return h.bean(type);
    }

    void reset() {
        h.reset();
        List.of("lr_notification", "lr_payment_statement_line", "lr_supplier_statement_line", "lr_recon_diff", "lr_recon_run", "lr_order_line_snapshot",
                "lr_sku_cost_version", "lr_refund_case", "lr_commission_entry", "lr_setting", "lr_audit_log").forEach(t -> h.jdbc.update("DELETE FROM " + t));
        payments.orders.clear();
        sms.sent.clear();
        sms.succeed = true;
    }

    Instant now() {
        return h.clock.instant();
    }

    static LocalDateTime utc(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    /** 交码完成的单卡订单；返回卡 id。 */
    long deliveredCard(String order, String member) {
        h.importPoolA(1, true);
        long card = h.acceptCard(order, h.catalog.skuANew, member);
        h.drain();
        return card;
    }

    void inUseUntil(long card, Instant expiresAt) {
        h.jdbc.update("UPDATE lr_card_asset SET status = 'IN_USE', expires_at = ? WHERE id = ?", utc(expiresAt), card);
    }

    @Override
    public void close() {
        h.close();
    }
}
