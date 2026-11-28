package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.liroam.common.testing.ItDatabase;

/**
 * R__liroam_menu：自研页面挂到底座顶栏（集成方案 §1.4 目标导航），「店铺」→「财务」、「日志」→「运维」，「关联管理」→「基础资料」，
 * 收款记录 / 退款流水随「流水」组进财务；不再有「旅行卡」顶栏。可重放；底座行只在仍是出厂值时改。没有 li_menu 时跳过（见 MigrationTest）。
 */
class MenuSeedTest {

    private static final String SCRIPT = "db/migration/liroam/R__liroam_menu.sql";
    private static final String OURS = "id BETWEEN 9100000000000000100 AND 9100000000000000299";
    private static final String NAV_PATH = """
            SELECT CONCAT(t.title, '/', g.title, '/', p.title) FROM li_menu p
              JOIN li_menu g ON p.parent_id = CAST(g.id AS CHAR) AND g.level = 1
              JOIN li_menu t ON g.parent_id = CAST(t.id AS CHAR) AND t.level = 0
             WHERE p.level = 2 AND (p.%s OR p.id IN (1372808148565360640, 1372808352295288832))
             ORDER BY 1""".formatted(OURS);

    @Test
    void placesLiroamPagesUnderShellNavigationAndReapplyRestoresIt() throws Exception {
        DataSource ds = ItDatabase.fresh("schema_menu");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createShellMenuTables(jdbc);
        jdbc.update("INSERT INTO li_role_menu (id, menu_id, role_id) VALUES (1, '9100000000000000100', 'r1'), (2, '9100000000000000111', 'r1')");

        LiroamMigrations.migrate(ds);

        assertThat(jdbc.queryForList(NAV_PATH, String.class)).containsExactlyInAnyOrder(
                "订单/卡与履约/卡台账", "订单/卡与履约/人工待办",
                "订单/售后/退款单", "订单/售后/退款规则",
                "订单/外部渠道/渠道与 SKU 映射", "订单/外部渠道/渠道订单",
                "商品/旅行卡配置/旅行卡商品", "商品/旅行卡配置/资费表与区域",
                "商品/定价/调价", "商品/定价/定价与汇率",
                "运营/分销管理/佣金策略", "运营/分销管理/佣金与提现",
                "统计/统计/经营毛利",
                "财务/流水/收款记录", "财务/流水/退款流水", "财务/对账/日对账",
                "运维/系统监控/运营告警", "运维/系统监控/运维概览");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_menu WHERE " + OURS + " AND level = 0", Integer.class))
                .as("不再有旅行卡顶栏").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_menu WHERE id IN "
                + "(9100000000000000100, 9100000000000000120, 9100000000000000150, 9100000000000000160)", Integer.class)).as("撤掉的分组已删").isZero();
        assertThat(jdbc.queryForList("SELECT menu_id FROM li_role_menu", String.class)).as("撤掉分组的角色授权一并清掉")
                .containsExactly("9100000000000000111");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_menu c LEFT JOIN li_menu p ON c.parent_id = CAST(p.id AS CHAR) "
                + "WHERE c." + OURS + " AND (p.id IS NULL OR p.level <> c.level - 1)", Integer.class)).as("无孤儿、层级连续").isZero();
        assertThat(jdbc.queryForObject("SELECT title FROM li_menu WHERE id = 1367044657296441344", String.class)).isEqualTo("基础资料");
        assertThat(jdbc.queryForList("SELECT title FROM li_menu WHERE level = 0 ORDER BY sort_order, id", String.class))
                .as("财务排在统计之后").containsExactly("订单", "商品", "运营", "统计", "财务", "设置", "运维");

        List<Map<String, Object>> leaves = jdbc.queryForList("SELECT name, path, front_route, permission FROM li_menu WHERE " + OURS + " AND level = 2");
        assertThat(leaves).hasSize(16).allSatisfy(leaf -> {
            assertThat((String) leaf.get("front_route")).startsWith("liroam/");
            assertThat((String) leaf.get("path")).startsWith("liroam-");
            assertThat((String) leaf.get("permission")).startsWith("/manager/liroam/");
        });
        assertThat(jdbc.queryForObject("SELECT permission FROM li_menu WHERE name = 'liroamCommissionPolicies'", String.class)).as("底价要选 SKU")
                .contains("/manager/liroam/catalog/skus*");
        assertThat(jdbc.queryForObject("SELECT permission FROM li_menu WHERE name = 'liroamCards'", String.class)).as("录物流要读壳物流公司")
                .contains("/manager/other/logistics*");

        int total = jdbc.queryForObject("SELECT COUNT(*) FROM li_menu", Integer.class);
        jdbc.update("UPDATE li_menu SET title = '被改', delete_flag = b'1' WHERE id = 9100000000000000111");
        jdbc.update("UPDATE li_menu SET title = '运维中心' WHERE id = 1348810864748945408");
        jdbc.update("UPDATE li_menu SET parent_id = '1367039534616805376' WHERE id = 1372807928452481024");
        reapply(ds);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_menu", Integer.class)).isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT CONCAT(title, '/', delete_flag + 0) FROM li_menu WHERE id = 9100000000000000111", String.class))
                .isEqualTo("卡台账/0");
        assertThat(jdbc.queryForObject("SELECT title FROM li_menu WHERE id = 1348810864748945408", String.class))
                .as("运营改过的底座标题不覆盖").isEqualTo("运维中心");
        assertThat(jdbc.queryForObject("SELECT parent_id FROM li_menu WHERE id = 1372807928452481024", String.class))
                .as("流水组退回出厂位置后重放再移进财务").isEqualTo("1367048084701315072");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 'lr\\_tmp%'",
                Integer.class)).isZero();
    }

    private static void reapply(DataSource ds) throws Exception {
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(SCRIPT), StandardCharsets.UTF_8));
        }
    }

    /** 本机库里底座菜单的出厂值（只取脚本挂接或改动到的行）。 */
    private static void createShellMenuTables(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE li_menu (
                  id bigint NOT NULL PRIMARY KEY, create_by varchar(255), create_time datetime, delete_flag bit(1), update_by varchar(255),
                  update_time datetime, description varchar(255), front_route varchar(255), icon varchar(255), level int, name varchar(255),
                  parent_id varchar(255), path varchar(255), sort_order decimal(10,2), title varchar(255), front_component varchar(255),
                  permission varchar(255)) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci""");
        jdbc.execute("CREATE TABLE li_role_menu (id bigint NOT NULL PRIMARY KEY, menu_id varchar(255), role_id varchar(255)) "
                + "CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci");
        jdbc.update("""
                INSERT INTO li_menu (id, level, name, title, parent_id, sort_order, front_route, path) VALUES
                  (1367039534616805376, 0, 'order', '订单', '0', 0.00, NULL, NULL),
                  (1367039950368800768, 0, 'goods', '商品', '0', 0.20, NULL, NULL),
                  (1367048084701315072, 0, 'shop', '店铺', '0', 0.40, NULL, NULL),
                  (1367040599596728320, 0, 'operate', '运营', '0', 0.50, NULL, NULL),
                  (1367040819248234496, 0, 'statistics', '统计', '0', 0.70, NULL, NULL),
                  (1348810750596767744, 0, 'settings', '设置', '0', 2.00, '1', '1'),
                  (1348810864748945408, 0, 'log', '日志', '0', 3.00, NULL, NULL),
                  (1367043505771249664, 1, 'aftersale', '售后', '1367039534616805376', 0.00, 'Main', '/'),
                  (1372807928452481024, 1, 'flow', '流水', '1367039534616805376', 3.00, 'Main', '/'),
                  (1367044657296441344, 1, 'association', '关联管理', '1367039950368800768', 1.00, 'Main', '/'),
                  (1374173575405109248, 1, 'distributionManager', '分销管理', '1367040599596728320', 1.00, 'Main', '/'),
                  (1367052616634204160, 1, 'statistics', '统计', '1367040819248234496', 0.00, 'Main', '/'),
                  (1349237928434098176, 1, 'log', '系统监控', '1348810864748945408', 1.00, 'Main', '/log'),
                  (1372808148565360640, 2, 'paymentLog', '收款记录', '1372807928452481024', 1.00, 'order/flow/paymentLog', 'paymentLog'),
                  (1372808352295288832, 2, 'refundLog', '退款流水', '1372807928452481024', 2.00, 'order/flow/refundLog', 'refundLog')""");
    }
}
