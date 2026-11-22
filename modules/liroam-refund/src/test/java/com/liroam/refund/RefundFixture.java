package com.liroam.refund;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.poll.CardRefresh;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;

/** 退款集成测试装配：履约两形状假上游 + 退款规则（dev 默认）+ 退款单 + 记录调用的佣金冲正。 */
final class RefundFixture implements AutoCloseable {

    record Reversal(long refundCaseId, String sourceLineRef, long refundFen, long paidFen) {
    }

    static final class RecordingReversal implements CommissionReversal {

        final List<Reversal> calls = new ArrayList<>();

        @Override
        public void reverse(long refundCaseId, String sourceLineRef, long refundFen, long paidFen) {
            calls.add(new Reversal(refundCaseId, sourceLineRef, refundFen, paidFen));
        }
    }

    final FulfillmentHarness h;
    final TestCatalog c;
    final RefundRules rules;
    final RefundCases cases;
    final RecordingReversal reversal = new RecordingReversal();

    private RefundFixture(FulfillmentHarness h) {
        this.h = h;
        this.c = h.catalog;
        JdbcClient jdbc = h.bean(JdbcClient.class);
        AuditLog audit = h.bean(AuditLog.class);
        TransactionTemplate tx = new TransactionTemplate(h.bean(PlatformTransactionManager.class));
        this.rules = new RefundRules(tx, jdbc, audit);
        this.cases = new RefundCases(tx, jdbc, rules, h.bean(CardActions.class), h.bean(CardRefresh.class), reversal, h.bean(Settings.class),
                audit, h.clock);
    }

    static RefundFixture start(String module) {
        return new RefundFixture(FulfillmentHarness.start(module));
    }

    void reset() {
        h.reset();
        h.jdbc.update("DELETE FROM lr_refund_case");
        h.jdbc.update("DELETE FROM lr_order_line_snapshot");
        h.jdbc.update("DELETE FROM lr_refund_rule");
        RefundRules.seedDevDefaults(h.bean(DataSource.class));
        reversal.calls.clear();
    }

    /** 模拟定价模块下单时写的订单行快照。 */
    void snapshot(String sourceType, String sourceLineRef, long skuBindingId, long paidFen, int qty) {
        h.jdbc.update("""
                INSERT INTO lr_order_line_snapshot (source_type, source_line_ref, sku_binding_id, supplier_id, card_product_id, region_id,
                                                    region_group_id, usage_region_text, unit_price_fen, paid_fen, qty)
                SELECT ?, ?, id, supplier_id, card_product_id, region_id, region_group_id, '测试区域', ?, ?, ? FROM lr_sku_binding WHERE id = ?
                """, sourceType, sourceLineRef, paidFen / qty, paidFen, qty, skuBindingId);
    }

    /** 供应商 A 一张已交码未激活的卡（池里带码）。 */
    long deliveredA(String order, String member) {
        h.importPoolA(1, true);
        long card = h.acceptCard(order, c.skuANew, member);
        h.drain();
        return card;
    }

    String cardStatus(long card) {
        return h.status("lr_card_asset", card);
    }

    boolean held(long card) {
        return h.jdbc.queryForObject("SELECT refund_hold FROM lr_card_asset WHERE id = ?", Boolean.class, card);
    }

    @Override
    public void close() {
        h.close();
    }
}
