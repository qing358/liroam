package com.liroam.fulfillment.testing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 两家供应商的最小目录种子：A 卖日本、B 卖美国，各一个新卡 SKU 与一个加包 SKU（均已上架、有 ACTIVE 售价）。
 * 壳 SKU id 形如 {@code SKU-A} / {@code SKU-A-TOP}。供应商指向哪两个适配器、各套餐的上游套餐码由 {@link Upstreams} 定。
 */
public final class TestCatalog {

    /** 两家供应商的适配器 Bean 名与四个 offering 的上游套餐码（A 新卡、A 加包、B 新卡、B 加包）。 */
    public record Upstreams(String adapterA, String adapterB, String baseA, String topUpA, String baseB, String topUpB) {

        static final Upstreams STUBS = new Upstreams(FulfillmentHarness.ADAPTER_A, FulfillmentHarness.ADAPTER_B, "BASE-1", "TOPUP-1", "BASE-1",
                "TOPUP-1");
    }

    public final long supplierA;
    public final long supplierB;
    public final long productA;
    public final long productB;
    public final long regionJp;
    public final long regionUs;
    public final long skuANew;
    public final long skuBNew;
    public final long skuATopUp;
    public final long skuBTopUp;
    public final long templateATopUp;

    private TestCatalog(JdbcTemplate jdbc, Upstreams up) {
        regionJp = insert(jdbc, "INSERT INTO lr_usage_region (code, name_zh, continent) VALUES ('JP', '日本', '亚洲')");
        regionUs = insert(jdbc, "INSERT INTO lr_usage_region (code, name_zh, continent) VALUES ('US', '美国', '北美洲')");
        supplierA = insert(jdbc, "INSERT INTO lr_supplier (code, name, adapter_key, enabled, listing_enabled) VALUES ('SUP_A', '供应商甲', '"
                + up.adapterA() + "', 1, 1)");
        supplierB = insert(jdbc, "INSERT INTO lr_supplier (code, name, adapter_key, enabled, listing_enabled) VALUES ('SUP_B', '供应商乙', '"
                + up.adapterB() + "', 1, 1)");
        productA = insert(jdbc, "INSERT INTO lr_card_product (supplier_id, home_location, card_type_code, name, allowed_forms) VALUES ("
                + supplierA + ", 'HK', 'A_T1', '亚洲漫游卡', 'ESIM')");
        productB = insert(jdbc, "INSERT INTO lr_card_product (supplier_id, home_location, card_type_code, name, allowed_forms, cap_voice, cap_sms) VALUES ("
                + supplierB + ", 'US', 'B_T1', '美国畅游卡', 'ESIM', 1, 1)");
        long tplA = template(jdbc, "日本 5 天 3GB", 3L << 30, 5);
        templateATopUp = template(jdbc, "日本加油包 1GB", 1L << 30, 5);
        long tplB = template(jdbc, "美国 7 天 5GB", 5L << 30, 7);
        long tplBTop = template(jdbc, "美国加油包 2GB", 2L << 30, 7);
        long offA = offering(jdbc, supplierA, productA, regionJp, tplA, "BASE", up.baseA());
        long offATop = offering(jdbc, supplierA, productA, regionJp, templateATopUp, "TOP_UP", up.topUpA());
        long offB = offering(jdbc, supplierB, productB, regionUs, tplB, "BASE", up.baseB());
        long offBTop = offering(jdbc, supplierB, productB, regionUs, tplBTop, "TOP_UP", up.topUpB());
        skuANew = sku(jdbc, "SKU-A", "G-A", "NEW_CARD", supplierA, productA, regionJp, tplA, offA);
        skuATopUp = sku(jdbc, "SKU-A-TOP", "G-A-TOP", "TOP_UP", supplierA, productA, regionJp, templateATopUp, offATop);
        skuBNew = sku(jdbc, "SKU-B", "G-B", "NEW_CARD", supplierB, productB, regionUs, tplB, offB);
        skuBTopUp = sku(jdbc, "SKU-B-TOP", "G-B-TOP", "TOP_UP", supplierB, productB, regionUs, tplBTop, offBTop);
        price(jdbc, skuANew, 4900);
        price(jdbc, skuATopUp, 1990);
        price(jdbc, skuBNew, 9900);
        price(jdbc, skuBTopUp, 2990);
    }

    static TestCatalog seed(JdbcTemplate jdbc) {
        return seed(jdbc, Upstreams.STUBS);
    }

    public static TestCatalog seed(JdbcTemplate jdbc, Upstreams upstreams) {
        return new TestCatalog(jdbc, upstreams);
    }

    private static long template(JdbcTemplate jdbc, String name, long quotaBytes, int days) {
        return insert(jdbc, "INSERT INTO lr_plan_template (name, data_quota_bytes, validity_days) VALUES ('" + name + "', " + quotaBytes + ", " + days + ")");
    }

    private static long offering(JdbcTemplate jdbc, long supplier, long product, long region, long template, String kind, String code) {
        return insert(jdbc, "INSERT INTO lr_supplier_offering (supplier_id, card_product_id, region_id, plan_template_id, kind, upstream_package_code) "
                + "VALUES (" + supplier + ", " + product + ", " + region + ", " + template + ", '" + kind + "', '" + code + "')");
    }

    private static long sku(JdbcTemplate jdbc, String shellSku, String goods, String kind, long supplier, long product, long region, long template,
            long offering) {
        return insert(jdbc, "INSERT INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, form, "
                + "plan_template_id, offering_id, listing_state) VALUES ('" + shellSku + "', '" + goods + "', '" + kind + "', " + supplier + ", "
                + product + ", " + region + ", 'ESIM', " + template + ", " + offering + ", 'LISTED')");
    }

    private static void price(JdbcTemplate jdbc, long sku, long fen) {
        jdbc.update("INSERT INTO lr_sku_price_version (sku_binding_id, price_cny_fen, cost_version_id, origin, status) VALUES (?, ?, 0, 'MANUAL', 'ACTIVE')",
                sku, fen);
    }

    private static long insert(JdbcTemplate jdbc, String sql) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
