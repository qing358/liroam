package com.liroam.shell.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRules;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.FulfillmentLines;
import com.liroam.schema.testing.ItSchema;

import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.service.OrderItemService;

/**
 * H1′：在与壳同口径的库上跑（li_ 表 utf8mb3、payment_time 存东八区本地时间、会话时区 Asia/Shanghai）。
 * 现在 = 2026-11-22 12:00 UTC = 20:00 东八区；回看 24h。
 */
class PaidOrderCompensationScannerTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    private static JdbcTemplate jdbc;
    private static PaidOrderCompensationScanner scanner;

    @BeforeAll
    static void setUp() {
        DataSource ds = ItSchema.migrated("shell_scanner");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE li_order (id BIGINT PRIMARY KEY, sn VARCHAR(255), member_id VARCHAR(255), order_status VARCHAR(255), "
                + "pay_status VARCHAR(255), payment_time DATETIME(6)) DEFAULT CHARSET = utf8mb3 COLLATE = utf8mb3_general_ci");
        jdbc.execute("CREATE TABLE li_order_item (id BIGINT PRIMARY KEY, sn VARCHAR(255), order_sn VARCHAR(255), sku_id VARCHAR(255), num INT) "
                + "DEFAULT CHARSET = utf8mb3 COLLATE = utf8mb3_general_ci");
        jdbc.update("INSERT INTO lr_supplier (id, code, name, adapter_key, enabled, listing_enabled) VALUES (1, 'SUP_A', 'A', 'sup_a', 1, 1)");
        jdbc.update("INSERT INTO lr_sku_binding (id, li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, form, "
                + "plan_template_id, offering_id, listing_state) VALUES (11, 'S-CARD', 'G', 'NEW_CARD', 1, 10, 100, 'ESIM', 1, 1, 'LISTED'), "
                + "(12, 'S-DRAFT', 'G', 'NEW_CARD', 1, 10, 100, 'ESIM', 1, 1, 'DRAFT')");

        OrderItemService orderItems = mock(OrderItemService.class);
        when(orderItems.getByOrderSn(anyString())).thenAnswer(inv -> jdbc.query(
                "SELECT sn, order_sn, sku_id, num FROM li_order_item WHERE order_sn = ? ORDER BY id", (rs, n) -> {
                    OrderItem i = new OrderItem();
                    i.setSn(rs.getString("sn"));
                    i.setOrderSn(rs.getString("order_sn"));
                    i.setSkuId(rs.getString("sku_id"));
                    i.setNum(rs.getInt("num"));
                    return i;
                }, inv.getArgument(0, String.class)));
        JdbcClient client = JdbcClient.create(ds);
        SkuBindings bindings = new SkuBindings(client);
        FulfillmentRules rules = new FulfillmentRules(bindings, new SupplierDirectory(client), new CardAssets(client));
        FulfillmentEntry entry = new FulfillmentEntry(rules, new FulfillmentLines(client));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        scanner = new PaidOrderCompensationScanner(client,
                new PaidOrderFulfillment(orderItems, bindings, new TopUpIntents(tx, client, rules, Clock.systemUTC()), entry), tx,
                new PaidOrderCompensationScanner.Settings(Duration.ofHours(24), ZoneId.of("Asia/Shanghai"), 200), NOW);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM li_order");
        jdbc.update("DELETE FROM li_order_item");
        jdbc.update("DELETE FROM lr_fulfillment_line");
    }

    private static long seq;

    private static void order(String sn, String payStatus, String paidAtShanghai, String... skus) {
        jdbc.update("INSERT INTO li_order (id, sn, member_id, order_status, pay_status, payment_time) VALUES (?, ?, 'm1', 'PAID', ?, ?)",
                ++seq, sn, payStatus, paidAtShanghai);
        for (String sku : skus) {
            jdbc.update("INSERT INTO li_order_item (id, sn, order_sn, sku_id, num) VALUES (?, ?, ?, ?, 2)", ++seq, sn + "-" + sku, sn, sku);
        }
    }

    private static int lines(String orderSn) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM lr_fulfillment_line WHERE source_ref = ?", Integer.class, orderSn);
    }

    @Test
    void recoversPaidOrderMissingItsLineAndOnlyTheBoundItems() {
        order("O-MISS", "PAID", "2026-11-22 19:00:00", "S-CARD", "S-PLAIN");

        assertThat(scanner.scan()).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT source_line_ref FROM lr_fulfillment_line", String.class)).containsExactly("O-MISS-S-CARD");
        assertThat(scanner.scan()).as("已补收的单不再被扫到").isZero();
    }

    @Test
    void ignoresUnpaidPlainAndAlreadyReceivedOrders() {
        order("O-UNPAID", "UNPAID", "2026-11-22 19:00:00", "S-CARD");
        order("O-PLAIN", "PAID", "2026-11-22 19:00:00", "S-PLAIN");
        order("O-DONE", "PAID", "2026-11-22 19:00:00", "S-CARD");
        jdbc.update("INSERT INTO lr_fulfillment_line (source_type, source_ref, source_line_ref, qty, sku_binding_id, status) "
                + "VALUES ('SHOP', 'O-DONE', 'O-DONE-S-CARD', 2, 11, 'RECEIVED')");

        assertThat(scanner.scan()).isZero();
        assertThat(lines("O-UNPAID")).isZero();
    }

    @Test
    void lookbackIsMeasuredInTheShellZone() {
        order("O-INSIDE", "PAID", "2026-11-21 20:30:00", "S-CARD");
        order("O-OUTSIDE", "PAID", "2026-11-21 19:30:00", "S-CARD");

        scanner.scan();

        assertThat(lines("O-INSIDE")).isEqualTo(1);
        assertThat(lines("O-OUTSIDE")).as("东八区 24h 之外").isZero();
    }

    @Test
    void oneRejectedOrderDoesNotBlockOthersAndRollsBackItsOwnLines() {
        order("O-BAD", "PAID", "2026-11-22 19:00:00", "S-CARD", "S-DRAFT");
        order("O-GOOD", "PAID", "2026-11-22 19:10:00", "S-CARD");

        assertThat(scanner.scan()).isEqualTo(1);

        assertThat(lines("O-GOOD")).isEqualTo(1);
        assertThat(lines("O-BAD")).as("同单另一行被拒，整单回滚").isZero();
    }
}
