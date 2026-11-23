package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.common.testing.ItDatabase;

/** 站点品牌：壳设置、页面装修与协议 / 关于我们文章里仍是上游 Lilishop 出厂值的字段换成轻游；运营改过的值不动。 */
class BrandSettingsTest {

    private static final String COMPANY = "想象智能（济南）有限公司";
    private static final String UPSTREAM_LOGO = "https://cdn.pickmall.cn/cdn/logo/logo2.png";
    private static final String UPSTREAM_ICON = "https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/default/388d86596b5342c2ba57c76b4f0f6a98.ico";
    private static final String UPSTREAM_PC_LOGO = "https://lilishop-pc.oss-cn-beijing.aliyuncs.com/static/logo/logo.png";
    private static final String UPSTREAM_ABOUT = "<p>关于，关于我们</p><p><img src=\"https://lilishop-oss.oss-cn-beijing.aliyuncs.com/b280f3d1b3504cbeb0a8dc0fab3a24ba.png\" /></p>";
    private static final String UPSTREAM_PRIVACY = "<p>APP隐私协议<br />\\n以下隐私协议是xxx公司（以下简称\"我们\"） 对用户隐私保护的许诺。</p>";

    @Test
    void replacesUpstreamDefaults() {
        JdbcTemplate jdbc = shellTables("schema_brand_upstream");
        jdbc.update("INSERT INTO li_setting (id, setting_value) VALUES ('BASE_SETTING', ?)", """
                {"staticPageAddress":"https://pc-b2b2c.pickmall.cn","website":"https://pickmall.cn","copyrightOwner":"lilishop",\
                "storeSideLogo":"%1$s","companyName":"北京宏业汇成科技有限公司","buyerSideIcon":"%2$s","siteName":"lilishop",\
                "companyUrl":"https://pickmall.cn","domainIcon":"%2$s","storeSideIcon":"%2$s","domainLogo":"%1$s",\
                "icp":"京ICP备20009696号-1","buyerSideLogo":"%1$s","staticPageWapAddress":"https://m-b2b2c.pickmall.cn"}"""
                .formatted(UPSTREAM_LOGO, UPSTREAM_ICON));
        jdbc.update("INSERT INTO li_setting (id, setting_value) VALUES ('VERIFICATION_SETTING', ?)",
                "{\"watermark\": \"LILI-SHOP\", \"interfereNum\": 1, \"loginBypassEnabled\": true}");
        jdbc.update("INSERT INTO li_setting (id, setting_value) VALUES ('SMS_SETTING', ?)", "{\"signName\":\"lili\",\"type\":\"ALI\"}");
        jdbc.update("INSERT INTO li_page_data (id, page_client_type, page_data) VALUES (1, 'PC', ?)",
                "{\"list\":[{\"options\":{\"logoUrl\":\"" + UPSTREAM_PC_LOGO + "\",\"placeholder\":\"请输入商品名称\"}}]}");
        jdbc.update("INSERT INTO li_page_data (id, page_client_type, page_data) VALUES (2, 'H5', ?)",
                "{\"name\":\"双十一活动\",\"title\":\"lilishop\",\"list\":[]}");
        article(jdbc, 1371780016521609216L, "关于我们", UPSTREAM_ABOUT);
        article(jdbc, 1371779927900160000L, "隐私策略", UPSTREAM_PRIVACY);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(base(jdbc, "siteName")).isEqualTo("轻游");
        assertThat(base(jdbc, "companyName")).isEqualTo(COMPANY);
        assertThat(base(jdbc, "copyrightOwner")).isEqualTo(COMPANY);
        for (String cleared : new String[] {"website", "companyUrl", "icp", "domainLogo", "buyerSideLogo", "storeSideLogo",
                "domainIcon", "buyerSideIcon", "storeSideIcon"}) {
            assertThat(base(jdbc, cleared)).as(cleared + " 清空后前端回落到内置轻游标识").isEmpty();
        }
        assertThat(base(jdbc, "staticPageAddress")).as("部署地址不是品牌字段").isEqualTo("https://pc-b2b2c.pickmall.cn");
        assertThat(field(jdbc, "VERIFICATION_SETTING", "watermark")).isEqualTo("LIROAM");
        assertThat(field(jdbc, "VERIFICATION_SETTING", "loginBypassEnabled")).isEqualTo("true");
        assertThat(field(jdbc, "SMS_SETTING", "signName")).isEqualTo("轻游");
        assertThat(pageData(jdbc, 1)).contains("\"logoUrl\":\"\"").contains("请输入商品名称").doesNotContain("lilishop");
        assertThat(pageData(jdbc, 2)).contains("\"title\":\"轻游\"").contains("双十一活动");
        assertThat(articleContent(jdbc, 1371780016521609216L)).isEqualTo("<p>轻游 Liroam</p><p>" + COMPANY + "</p>");
        assertThat(articleContent(jdbc, 1371779927900160000L)).contains("以下隐私协议是" + COMPANY + "（以下简称").doesNotContain("xxx公司");
    }

    @Test
    void keepsValuesOperatorsChanged() {
        JdbcTemplate jdbc = shellTables("schema_brand_custom");
        String base = "{\"siteName\":\"环球卡铺\",\"companyName\":\"某某公司\",\"copyrightOwner\":\"某某\",\"domainLogo\":\"https://x/logo.png\","
                + "\"icp\":\"鲁ICP备1号\",\"website\":\"https://x\"}";
        jdbc.update("INSERT INTO li_setting (id, setting_value) VALUES ('BASE_SETTING', ?)", base);
        jdbc.update("INSERT INTO li_setting (id, setting_value) VALUES ('SMS_SETTING', ?)", "{\"signName\":\"环球\"}");
        String page = "{\"list\":[{\"options\":{\"logoUrl\":\"https://x/logo.png\"}}],\"title\":\"环球\"}";
        jdbc.update("INSERT INTO li_page_data (id, page_client_type, page_data) VALUES (1, 'PC', ?)", page);
        String about = "<p>环球卡铺是一家卡券平台。</p>";
        article(jdbc, 1371780016521609216L, "关于我们", about);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(base(jdbc, "siteName")).isEqualTo("环球卡铺");
        assertThat(base(jdbc, "companyName")).isEqualTo("某某公司");
        assertThat(base(jdbc, "copyrightOwner")).isEqualTo("某某");
        assertThat(base(jdbc, "domainLogo")).isEqualTo("https://x/logo.png");
        assertThat(base(jdbc, "icp")).isEqualTo("鲁ICP备1号");
        assertThat(base(jdbc, "website")).isEqualTo("https://x");
        assertThat(base(jdbc, "buyerSideLogo")).as("缺的键不补").isNull();
        assertThat(field(jdbc, "SMS_SETTING", "signName")).isEqualTo("环球");
        assertThat(pageData(jdbc, 1)).isEqualTo(page);
        assertThat(articleContent(jdbc, 1371780016521609216L)).isEqualTo(about);
    }

    private static JdbcTemplate shellTables(String module) {
        DataSource ds = ItDatabase.fresh(module);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE li_setting (id varchar(255) NOT NULL PRIMARY KEY, create_by varchar(64), create_time datetime(6), delete_flag bit(1),
                  update_by varchar(64), update_time datetime(6), setting_value mediumtext) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin""");
        jdbc.execute("""
                CREATE TABLE li_page_data (id bigint NOT NULL PRIMARY KEY, create_by varchar(255), create_time datetime(6), delete_flag bit(1),
                  update_by varchar(255), update_time datetime(6), name varchar(255), num varchar(255), page_client_type varchar(255),
                  page_data longtext, page_show varchar(255), page_type varchar(255)) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci""");
        jdbc.execute("""
                CREATE TABLE li_article (id bigint NOT NULL PRIMARY KEY, create_by varchar(255), create_time datetime, delete_flag bit(1),
                  update_by varchar(255), update_time datetime, category_id varchar(255) NOT NULL, content text, sort int,
                  title varchar(30), type varchar(255), open_status bit(1)) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci""");
        return jdbc;
    }

    private static String base(JdbcTemplate jdbc, String key) {
        return field(jdbc, "BASE_SETTING", key);
    }

    private static String field(JdbcTemplate jdbc, String id, String key) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(setting_value, ?)) FROM li_setting WHERE id = ?", String.class, "$." + key, id);
    }

    private static void article(JdbcTemplate jdbc, long id, String title, String content) {
        jdbc.update("INSERT INTO li_article (id, category_id, title, content) VALUES (?, '1371779742369316864', ?, ?)", id, title, content);
    }

    private static String articleContent(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT content FROM li_article WHERE id = ?", String.class, id);
    }

    private static String pageData(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT page_data FROM li_page_data WHERE id = ?", String.class, id);
    }
}
