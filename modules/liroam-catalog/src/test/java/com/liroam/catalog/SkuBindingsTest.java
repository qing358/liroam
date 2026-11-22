package com.liroam.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.SkuBinding.ListingState;
import com.liroam.catalog.SkuBinding.SkuKind;
import com.liroam.schema.testing.ItSchema;

class SkuBindingsTest {

    private static SkuBindings bindings;
    private static SupplierDirectory suppliers;

    @BeforeAll
    static void seed() {
        var ds = ItSchema.migrated("catalog_bindings");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO lr_supplier (id, code, name, adapter_key, enabled, listing_enabled, rate_limit_per_min) VALUES (7, 'SUP_A', 'A', 'sup_a', 1, 1, 90)");
        jdbc.update("INSERT INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_group_id, form, "
                + "plan_template_id, offering_id, listing_state, numbered_gate) VALUES ('1001', 'G1', 'NEW_CARD', 7, 10, 3, 'ESIM', 1, 2, 'LISTED', 1)");
        jdbc.update("INSERT INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, form, "
                + "plan_template_id, offering_id) VALUES ('1002', 'G1', 'TOP_UP', 7, 10, 100, 'SOFT', 1, 3)");
        JdbcClient client = JdbcClient.create(ds);
        bindings = new SkuBindings(client);
        suppliers = new SupplierDirectory(client);
    }

    @Test
    void mapsBindingColumnsToDomainTerms() {
        SkuBinding b = bindings.findByShellSkuId("1001").orElseThrow();

        assertThat(b.shellGoodsId()).isEqualTo("G1");
        assertThat(b.skuKind()).isEqualTo(SkuKind.NEW_CARD);
        assertThat(b.supplierId()).isEqualTo(7);
        assertThat(b.regionId()).isNull();
        assertThat(b.regionGroupId()).isEqualTo(3L);
        assertThat(b.listingState()).isEqualTo(ListingState.LISTED);
        assertThat(b.numberedGate()).isTrue();
        assertThat(bindings.findByShellSkuId("1002").orElseThrow().listingState()).isEqualTo(ListingState.DRAFT);
    }

    @Test
    void unboundSkusAreAbsentFromBatchLookup() {
        Map<String, SkuBinding> found = bindings.findByShellSkuIds(List.of("1001", "1002", "9999"));

        assertThat(found).containsOnlyKeys("1001", "1002");
        assertThat(bindings.findByShellSkuIds(List.of())).isEmpty();
        assertThat(bindings.findByShellSkuId("9999")).isEmpty();
    }

    @Test
    void readsSupplierFlags() {
        Supplier s = suppliers.find(7).orElseThrow();

        assertThat(s.listingEnabled()).isTrue();
        assertThat(s.rateLimitPerMin()).isEqualTo(90);
        assertThat(suppliers.enabled()).extracting(Supplier::code).containsExactly("SUP_A");
    }
}
