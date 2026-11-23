package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.common.testing.ItDatabase;

/**
 * 上游残留：平台首页装修（PC / H5）、会员与 admin 图片、上游部署地址。
 * 仍是上游出厂值时换成轻游内容或清空；运营改过的不动；换上的站内素材在前端 public 里确实存在。
 */
class BrandResidueTest {

    private static final long PC = 2115743299818201089L;
    private static final long H5 = 2112712039703707649L;
    private static final String OSS = "https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/2117947015370043393/";
    private static final String UPSTREAM_AVATAR = "https://lilishop-oss.oss-cn-beijing.aliyuncs.com/65e87ffa718b42bb9c201712566dbc9a.png";
    private static final String UPSTREAM_H5 = """
            {"name":"双十一活动","title":"轻游","bgColor":"#FFF2F2","topColor":"#C80A1E","list":[{"type":"homeComb","options":{\
            "logoFixUrl":"https://lilishop-oss.oss-cn-beijing.aliyuncs.com/MANAGER/2112709632299044865/2501af29fb6244488d9b84c4035b307c.png",\
            "placeWords":"双11跨店每满300减50","hotWords":["双11跨店每满300","iPhone 16 "]}},{"type":"shop","options":{"list":[{"title":"✦ 双11大牌官方旗舰店"}]}},\
            {"type":"promotionDetail","options":{"list":[{"data":[{"goodsName":"一家很有品的店","thumbnail":"/demo/apple-phone.png"}]}]}}]}""";

    @Test
    void pcHomeLosesUpstreamSamples() throws IOException {
        JdbcTemplate jdbc = shellTables("schema_residue_pc");
        jdbc.update("INSERT INTO li_page_data (id, name, page_client_type, page_data) VALUES (?, '平台首页', 'PC', ?)", PC, resource("brand/pc-home-upstream.json"));

        LiroamMigrations.migrate(jdbc.getDataSource());

        String page = pageData(jdbc, PC);
        assertThat(json(jdbc, PC, "$.list[1].components[0].options.hotWords")).isEqualTo("[\"日本\", \"韩国\", \"泰国\", \"欧洲多国\", \"美国\"]");
        assertThat(text(jdbc, PC, "$.list[2].components[0].options.activeColor")).isEqualTo("#0B7A75");
        assertThat(json(jdbc, PC, "$.list[2].components[1].options.list[*].img"))
                .isEqualTo("[\"/liroam/decorate/banner-1.svg\", \"/liroam/decorate/banner-2.svg\", \"/liroam/decorate/banner-3.svg\"]");
        assertThat(json(jdbc, PC, "$.list[3].components[0].options.cards[*].title")).isEqualTo("[\"限时特价\", \"新上架\", \"续费加包\", \"领券中心\"]");
        assertThat(text(jdbc, PC, "$.list[4].components[0].options.moreUrl")).isEqualTo("/goodsList");
        assertThat(text(jdbc, PC, "$.list[5].components[0].options.categoryId")).as("按上架商品自动取数").isEmpty();
        assertThat(json(jdbc, PC, "$.list[5].components[0].options.goods")).isEqualTo("[]");
        assertThat(json(jdbc, PC, "$.list[*].components[0].type"))
                .isEqualTo("[\"topBar\", \"searchHeader\", \"categoryMegaMenu\", \"promoGroup\", \"titleBar\", \"goodsList\"]");
        assertThat(text(jdbc, PC, "$.list[0].components[0].options.welcomeText")).isEqualTo("欢迎来到轻游");
        assertThat(json(jdbc, PC, "$.list[2].components[0].options.categories[*].name"))
                .as("分类菜单自定义占位换成按目的地分区").isEqualTo("[\"热门目的地\", \"欧美澳新\", \"港澳台\", \"按卡型选\"]");
        assertThat(text(jdbc, PC, "$.list[2].components[0].options.source")).isEqualTo("system");
        assertThat(page).doesNotContain("aliyuncs").doesNotContain("test 11").doesNotContain("test 123").doesNotContain("华为")
                .doesNotContain("HuaweiMate").doesNotContain("409EFF").doesNotContain("手机").doesNotContain("1348576427268399")
                .doesNotContain("精选频道").doesNotContain("企业采购").doesNotContain("生鲜补贴").doesNotContain("母婴满减");
        assertSiteAssetsExist(page, "buyer", "manager");
    }

    @Test
    void pcHomeKeepsComponentsOperatorsChanged() throws IOException {
        JdbcTemplate jdbc = shellTables("schema_residue_pc_custom");
        String upstream = resource("brand/pc-home-upstream.json");
        String custom = upstream.replace("\"手机\",\"数码家电\"", "\"冰岛\",\"数码家电\"").replace("#409EFF", "#123456")
                .replace("static/carousel/banner_1.png", "static/carousel/mine.png").replace("1945777974252118018", "1")
                .replace("/goodsList?categoryId=1348576427268399274", "/goodsList?categoryId=9").replace("\"母婴满减\"", "\"冰岛专享\"");
        jdbc.update("INSERT INTO li_page_data (id, name, page_client_type, page_data) VALUES (?, '平台首页', 'PC', ?)", PC, custom);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(json(jdbc, PC, "$.list[1].components[0].options.hotWords")).startsWith("[\"冰岛\"");
        assertThat(text(jdbc, PC, "$.list[2].components[0].options.activeColor")).isEqualTo("#123456");
        assertThat(text(jdbc, PC, "$.list[2].components[1].options.list[0].img")).endsWith("mine.png");
        assertThat(text(jdbc, PC, "$.list[4].components[0].options.moreUrl")).isEqualTo("/goodsList?categoryId=9");
        assertThat(text(jdbc, PC, "$.list[6].components[0].type")).as("运营换过商品的推荐流保留").isEqualTo("recommendFeed");
        assertThat(json(jdbc, PC, "$.list[3].components[0].options.cards[0].title")).as("未改的组件照常换").isEqualTo("\"限时特价\"");
        assertThat(json(jdbc, PC, "$.list[2].components[0].options.categories[0].name")).as("改过的分类菜单占位保留").isEqualTo("\"精选频道\"");
    }

    @Test
    void h5Double11SampleBecomesLiroamHome() {
        JdbcTemplate jdbc = shellTables("schema_residue_wap");
        jdbc.update("INSERT INTO li_page_data (id, name, page_client_type, page_data) VALUES (?, '双十一活动', 'H5', ?)", H5, UPSTREAM_H5);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(jdbc.queryForObject("SELECT name FROM li_page_data WHERE id = ?", String.class, H5)).isEqualTo("平台首页");
        String page = pageData(jdbc, H5);
        assertThat(text(jdbc, H5, "$.topColor")).isEqualTo("#0B7A75");
        assertThat(json(jdbc, H5, "$.list[0].options.hotWords")).isEqualTo("[\"日本\", \"韩国\", \"泰国\", \"欧洲多国\", \"美国\"]");
        assertThat(page).doesNotContain("aliyuncs").doesNotContain("/demo/").doesNotContain("双11").doesNotContain("一家很有品")
                .doesNotContain("iPhone").doesNotContain("1348576427");
        assertThat(page.toLowerCase()).doesNotContain("#e1212b").doesNotContain("#ff2200").doesNotContain("#ff5c58")
                .doesNotContain("#c80a1e").doesNotContain("#d80c18").doesNotContain("#fff2f2");
        assertSiteAssetsExist(page, "manager");
    }

    @Test
    void h5PageOperatorsRenamedStays() {
        JdbcTemplate jdbc = shellTables("schema_residue_wap_custom");
        String custom = UPSTREAM_H5.replace("\"name\":\"双十一活动\"", "\"name\":\"春季上新\"");
        jdbc.update("INSERT INTO li_page_data (id, name, page_client_type, page_data) VALUES (?, '春季上新', 'H5', ?)", H5, custom);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(pageData(jdbc, H5)).isEqualTo(custom);
    }

    @Test
    void upstreamImagesAndAddressesReplaced() {
        JdbcTemplate jdbc = shellTables("schema_residue_assets");
        setting(jdbc, "BASE_SETTING", "{\"siteName\":\"轻游\",\"staticPageAddress\":\"https://pc-b2b2c.pickmall.cn\",\"staticPageWapAddress\":\"https://m-b2b2c.pickmall.cn\"}");
        setting(jdbc, "CONNECT_SETTING", "{\"callbackUrl\":\"https://buyer-api.pickmall.cn\",\"pc\":\"https://pc-b2b2c.pickmall.cn\",\"wap\":\"https://m-b2b2c.pickmall.cn\"}");
        setting(jdbc, "ALIPAY_PAYMENT", "{\"appId\":\"appId\",\"callbackUrl\":\"https://buyer-api.pickmall.cn\"}");
        setting(jdbc, "PAID_MEMBER_SETTING", "{\"name\":\"付费会员\",\"icon\":\"" + OSS + "28bf6388af504bf6990bbdc6ba296806.png\"}");
        String[] grades = {"4c9dee6a9f3a49e29149e2a73f915122", "7bbe054695754b9fbf18fa17fefcd542", "6be4279660174865a6e066986bb6e35e", "14e1d00ea53245f78ed213cf334f41f4"};
        String[] cards = {"d6d4dcda15de41a7a3d32d0285395c04", "9ac25f921c6e44a58473f3b85bede739", "fc96739b017e4f2fbef26b1358778362", "a6b486be1852414c95bca905e251b1ad"};
        for (int i = 0; i < grades.length; i++) {
            jdbc.update("INSERT INTO li_member_grade (id, grade_image, grade_background) VALUES (?, ?, ?)", i + 1, OSS + grades[i] + ".png", OSS + cards[i] + ".jpg");
        }
        String[] benefits = {"e629982542f64f3db75020e5e842788c", "88d48600589c41d7b97a5a35097cf4e0", "cf34dbbda09946489bf5b0231860d012",
            "58e629e0915e481181ca574371688759", "35b640facdc543db9b57a2b47643816a", "54cf95fa0ed6400eba268181d4d43401",
            "c639048c3fb5415a9e0f8b32c6a69f4b", "a5243dbf74d94cc982ee28641c66c3b4", "26898929300f40efb9fb3edd05937d74", "c94b9dac3d2a41dc9070b23c9237e3b3"};
        for (int i = 0; i < benefits.length; i++) {
            jdbc.update("INSERT INTO li_member_benefit (id, benefit_logo) VALUES (?, ?)", i + 1, OSS + benefits[i] + ".png");
        }
        jdbc.update("INSERT INTO li_admin_user (id, username, avatar) VALUES (1337306110277476352, 'admin', ?)", UPSTREAM_AVATAR);

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(field(jdbc, "BASE_SETTING", "staticPageAddress")).isEmpty();
        assertThat(field(jdbc, "BASE_SETTING", "staticPageWapAddress")).isEmpty();
        assertThat(field(jdbc, "BASE_SETTING", "siteName")).isEqualTo("轻游");
        assertThat(field(jdbc, "CONNECT_SETTING", "callbackUrl")).isEmpty();
        assertThat(field(jdbc, "CONNECT_SETTING", "pc")).isEmpty();
        assertThat(field(jdbc, "CONNECT_SETTING", "wap")).isEmpty();
        assertThat(field(jdbc, "ALIPAY_PAYMENT", "callbackUrl")).isEmpty();
        assertThat(field(jdbc, "ALIPAY_PAYMENT", "appId")).isEqualTo("appId");
        assertThat(field(jdbc, "PAID_MEMBER_SETTING", "icon")).isEqualTo("/liroam/member/paid.svg");
        assertThat(jdbc.queryForList("SELECT grade_image FROM li_member_grade ORDER BY id", String.class))
                .containsExactly("/liroam/member/grade-1.svg", "/liroam/member/grade-2.svg", "/liroam/member/grade-3.svg", "/liroam/member/grade-4.svg");
        List<String> cardImages = jdbc.queryForList("SELECT grade_background FROM li_member_grade ORDER BY id", String.class);
        assertThat(cardImages).containsExactly("/liroam/member/grade-card-1.svg", "/liroam/member/grade-card-2.svg",
                "/liroam/member/grade-card-3.svg", "/liroam/member/grade-card-4.svg");
        assertSiteAssetsExist(String.join(" ", cardImages), "buyer", "manager");
        List<String> logos = jdbc.queryForList("SELECT benefit_logo FROM li_member_benefit ORDER BY id", String.class);
        assertThat(logos).allMatch(s -> s.startsWith("/liroam/member/benefit-"));
        assertThat(logos.get(1)).isEqualTo("/liroam/member/benefit-service.svg");
        String avatar = jdbc.queryForObject("SELECT avatar FROM li_admin_user", String.class);
        assertThat(avatar).isEqualTo("/liroam/avatar-admin.svg");
        assertSiteAssetsExist(String.join(" ", logos) + " /liroam/member/paid.svg /liroam/member/grade-4.svg", "buyer", "manager");
        assertSiteAssetsExist(avatar, "manager");
    }

    @Test
    void imagesAndAddressesOperatorsSetStay() {
        JdbcTemplate jdbc = shellTables("schema_residue_assets_custom");
        String base = "{\"staticPageAddress\":\"https://www.liroam.example\"}";
        setting(jdbc, "BASE_SETTING", base);
        setting(jdbc, "ALIPAY_PAYMENT", "{\"callbackUrl\":\"https://api.liroam.example\"}");
        jdbc.update("INSERT INTO li_member_grade (id, grade_image, grade_background) VALUES (1, 'https://cdn.liroam.example/g1.png', 'https://cdn.liroam.example/c1.jpg')");
        jdbc.update("INSERT INTO li_admin_user (id, username, avatar) VALUES (1337306110277476352, 'admin', 'https://cdn.liroam.example/a.png')");

        LiroamMigrations.migrate(jdbc.getDataSource());

        assertThat(field(jdbc, "BASE_SETTING", "staticPageAddress")).isEqualTo("https://www.liroam.example");
        assertThat(field(jdbc, "ALIPAY_PAYMENT", "callbackUrl")).isEqualTo("https://api.liroam.example");
        assertThat(jdbc.queryForObject("SELECT grade_image FROM li_member_grade", String.class)).isEqualTo("https://cdn.liroam.example/g1.png");
        assertThat(jdbc.queryForObject("SELECT grade_background FROM li_member_grade", String.class)).isEqualTo("https://cdn.liroam.example/c1.jpg");
        assertThat(jdbc.queryForObject("SELECT avatar FROM li_admin_user", String.class)).isEqualTo("https://cdn.liroam.example/a.png");
    }

    private static final Pattern SITE_ASSET = Pattern.compile("/liroam/[a-z0-9/-]+\\.svg");

    private static void assertSiteAssetsExist(String text, String... apps) {
        Matcher m = SITE_ASSET.matcher(text);
        int found = 0;
        while (m.find()) {
            found++;
            for (String app : apps) {
                assertThat(repoRoot().resolve("shop/lilishop-ui/" + app + "/public" + m.group())).as(app + " 缺 " + m.group()).isRegularFile();
            }
        }
        assertThat(found).as("应引用站内素材").isPositive();
    }

    private static Path repoRoot() {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("deploy/ports.env"))) {
                return p;
            }
        }
        throw new IllegalStateException("找不到仓根");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = BrandResidueTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JdbcTemplate shellTables(String module) {
        JdbcTemplate jdbc = new JdbcTemplate(ItDatabase.fresh(module));
        jdbc.execute("""
                CREATE TABLE li_setting (id varchar(255) NOT NULL PRIMARY KEY, create_by varchar(64), create_time datetime(6), delete_flag bit(1),
                  update_by varchar(64), update_time datetime(6), setting_value mediumtext) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin""");
        jdbc.execute("""
                CREATE TABLE li_page_data (id bigint NOT NULL PRIMARY KEY, create_by varchar(255), create_time datetime(6), delete_flag bit(1),
                  update_by varchar(255), update_time datetime(6), name varchar(255), num varchar(255), page_client_type varchar(255),
                  page_data longtext, page_show varchar(255), page_type varchar(255)) CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci""");
        jdbc.execute("CREATE TABLE li_member_grade (id bigint NOT NULL PRIMARY KEY, grade_name varchar(255), grade_image varchar(255), grade_background varchar(500))");
        jdbc.execute("CREATE TABLE li_member_benefit (id bigint NOT NULL PRIMARY KEY, benefit_name varchar(255), benefit_logo varchar(500))");
        jdbc.execute("CREATE TABLE li_admin_user (id bigint NOT NULL PRIMARY KEY, username varchar(255), avatar varchar(1000))");
        return jdbc;
    }

    private static void setting(JdbcTemplate jdbc, String id, String value) {
        jdbc.update("INSERT INTO li_setting (id, setting_value) VALUES (?, ?)", id, value);
    }

    private static String field(JdbcTemplate jdbc, String id, String key) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(setting_value, ?)) FROM li_setting WHERE id = ?", String.class, "$." + key, id);
    }

    private static String pageData(JdbcTemplate jdbc, long id) {
        return jdbc.queryForObject("SELECT page_data FROM li_page_data WHERE id = ?", String.class, id);
    }

    private static String json(JdbcTemplate jdbc, long id, String path) {
        return jdbc.queryForObject("SELECT CAST(JSON_EXTRACT(page_data, ?) AS CHAR) FROM li_page_data WHERE id = ?", String.class, path, id);
    }

    private static String text(JdbcTemplate jdbc, long id, String path) {
        return jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(page_data, ?)) FROM li_page_data WHERE id = ?", String.class, path, id);
    }
}
