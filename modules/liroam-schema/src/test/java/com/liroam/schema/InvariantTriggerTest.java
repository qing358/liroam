package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.schema.testing.ItSchema;

/** 数据库兜底的不变式：SKU 上架即锁（CT-CAT-01）、卡属主只写一次、卡不换供应商。 */
class InvariantTriggerTest {

    private static JdbcTemplate jdbc;
    private static int unitSeq;

    @BeforeAll
    static void migrate() {
        jdbc = new JdbcTemplate(ItSchema.migrated("schema_triggers"));
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM lr_sku_binding");
        jdbc.update("DELETE FROM lr_card_asset");
    }

    private long insertSku(String skuId, String state) {
        jdbc.update("INSERT INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, form, "
                + "plan_template_id, offering_id, listing_state) VALUES (?, 'G1', 'NEW_CARD', 1, 10, 100, 'ESIM', 1, 1, ?)", skuId, state);
        return jdbc.queryForObject("SELECT id FROM lr_sku_binding WHERE li_goods_sku_id = ?", Long.class, skuId);
    }

    @Test
    void draftSkuCanBeRebound() {
        long id = insertSku("S-DRAFT", "DRAFT");

        jdbc.update("UPDATE lr_sku_binding SET supplier_id = 2, region_id = 101 WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT supplier_id FROM lr_sku_binding WHERE id = ?", Long.class, id)).isEqualTo(2L);
    }

    @Test
    void firstListingLocksAndLockedSkuRefusesSupplierOrRegionChange() {
        long id = insertSku("S-LIST", "DRAFT");
        jdbc.update("UPDATE lr_sku_binding SET listing_state = 'LISTED' WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT locked_at IS NOT NULL FROM lr_sku_binding WHERE id = ?", Boolean.class, id)).isTrue();

        assertThatThrownBy(() -> jdbc.update("UPDATE lr_sku_binding SET supplier_id = 2 WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("LR_SKU_LOCKED");
        assertThatThrownBy(() -> jdbc.update("UPDATE lr_sku_binding SET region_id = 101 WHERE id = ?", id))
                .hasMessageContaining("LR_SKU_LOCKED");
        assertThatThrownBy(() -> jdbc.update("UPDATE lr_sku_binding SET region_id = NULL, region_group_id = 5 WHERE id = ?", id))
                .hasMessageContaining("LR_SKU_LOCKED");
        assertThatThrownBy(() -> jdbc.update("UPDATE lr_sku_binding SET card_product_id = 11 WHERE id = ?", id))
                .hasMessageContaining("LR_SKU_LOCKED");
        assertThatThrownBy(() -> jdbc.update("UPDATE lr_sku_binding SET locked_at = NULL WHERE id = ?", id))
                .hasMessageContaining("LR_SKU_LOCKED");
    }

    @Test
    void lockedSkuCanStillBeDelistedAndRelisted() {
        long id = insertSku("S-INSERT-LISTED", "LISTED");
        assertThat(jdbc.queryForObject("SELECT locked_at IS NOT NULL FROM lr_sku_binding WHERE id = ?", Boolean.class, id)).isTrue();

        jdbc.update("UPDATE lr_sku_binding SET listing_state = 'DELISTED' WHERE id = ?", id);
        jdbc.update("UPDATE lr_sku_binding SET listing_state = 'LISTED' WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT listing_state FROM lr_sku_binding WHERE id = ?", String.class, id)).isEqualTo("LISTED");
    }

    @Test
    void skuMustNameExactlyOneUsageRegion() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, "
                + "region_id, region_group_id, form, plan_template_id, offering_id) VALUES ('S-BOTH', 'G1', 'NEW_CARD', 1, 10, 100, 5, 'ESIM', 1, 1)"))
                .isInstanceOf(DataAccessException.class);
    }

    private long insertCard(String owner) {
        jdbc.update("INSERT INTO lr_card_asset (fulfillment_line_id, unit_seq, supplier_id, card_product_id, region_id, form, owner_member_id) "
                + "VALUES (1, ?, 1, 10, 100, 'ESIM', ?)", ++unitSeq, owner);
        return jdbc.queryForObject("SELECT MAX(id) FROM lr_card_asset", Long.class);
    }

    @Test
    void cardOwnerIsWrittenOnce() {
        long channelCard = insertCard(null);
        jdbc.update("UPDATE lr_card_asset SET owner_member_id = 'M1' WHERE id = ?", channelCard);
        assertThat(jdbc.queryForObject("SELECT owner_bound_at IS NOT NULL FROM lr_card_asset WHERE id = ?", Boolean.class, channelCard)).isTrue();

        assertThatThrownBy(() -> jdbc.update("UPDATE lr_card_asset SET owner_member_id = 'M2' WHERE id = ?", channelCard))
                .hasMessageContaining("LR_CARD_OWNER_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("UPDATE lr_card_asset SET owner_member_id = NULL WHERE id = ?", channelCard))
                .hasMessageContaining("LR_CARD_OWNER_IMMUTABLE");

        long shopCard = insertCard("M3");
        assertThat(jdbc.queryForObject("SELECT owner_bound_at IS NOT NULL FROM lr_card_asset WHERE id = ?", Boolean.class, shopCard)).isTrue();
        jdbc.update("UPDATE lr_card_asset SET status = 'ALLOCATED' WHERE id = ?", shopCard);
    }

    @Test
    void cardNeverChangesSupplier() {
        long card = insertCard("M1");

        assertThatThrownBy(() -> jdbc.update("UPDATE lr_card_asset SET supplier_id = 2 WHERE id = ?", card))
                .hasMessageContaining("LR_CARD_SUPPLIER_IMMUTABLE");
    }
}
