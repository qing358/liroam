package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.common.testing.ItDatabase;

/** 新买家站帮助中心挂在壳文章分类上：根「帮助中心」与设计稿 15 的话题分类、常见问题按固定 id 建好，运营只管写文章。 */
class HelpArticleCategoriesTest {

    private static final String ROOT = "9100000000000003000";

    @Test
    void createsHelpRootAndTopicsWithFixedIds() {
        JdbcTemplate jdbc = shellTables("schema_help_categories");

        LiroamMigrations.migrate(jdbc.getDataSource());

        Map<String, Object> root = jdbc.queryForMap(
                "SELECT article_category_name, parent_id, level, type, delete_flag + 0 AS deleted FROM li_article_category WHERE id = ?", ROOT);
        assertThat(root).containsEntry("article_category_name", "帮助中心").containsEntry("parent_id", "0")
                .containsEntry("level", 0).containsEntry("type", "OTHER");
        assertThat(((Number) root.get("deleted")).intValue()).as("壳按 delete_flag = 0 读未删除的分类").isZero();

        List<String> children = jdbc.queryForList(
                "SELECT CONCAT(id, ' ', article_category_name) FROM li_article_category WHERE parent_id = ? AND level = 1 AND delete_flag = 0 ORDER BY sort",
                String.class, ROOT);
        assertThat(children).containsExactly(
                "9100000000000003001 设备兼容",
                "9100000000000003002 安装与开卡",
                "9100000000000003003 用量与加包",
                "9100000000000003004 退款与售后",
                "9100000000000003005 渠道订单认领",
                "9100000000000003006 境外上网设置",
                "9100000000000003009 常见问题");
    }

    @Test
    void keepsCategoriesOperatorsAlreadyHave() {
        JdbcTemplate jdbc = shellTables("schema_help_categories_custom");
        jdbc.update("INSERT INTO li_article_category (id, article_category_name, parent_id, level, sort, type, delete_flag) "
                + "VALUES (9100000000000003001, '支持的手机', ?, 1, 5, 'OTHER', b'0')", ROOT);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("SELECT article_category_name FROM li_article_category WHERE id = 9100000000000003001", String.class))
                .isEqualTo("支持的手机");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM li_article_category WHERE parent_id = ?", Integer.class, ROOT)).isEqualTo(7);
    }

    private static JdbcTemplate shellTables(String module) {
        DataSource ds = ItDatabase.fresh(module);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE li_article_category (id bigint NOT NULL PRIMARY KEY, create_by varchar(255), create_time datetime, delete_flag bit(1),
                  update_by varchar(255), update_time datetime, level int, parent_id varchar(255), sort int DEFAULT 0,
                  article_category_name varchar(255), type varchar(255)) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci""");
        return jdbc;
    }
}
