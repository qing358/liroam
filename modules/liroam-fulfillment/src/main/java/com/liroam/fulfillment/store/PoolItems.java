package com.liroam.fulfillment.store;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.crypto.SensitiveCipher.Sealed;

/** 卡池（{@code lr_card_pool_item}）：IN_STOCK → ALLOCATED（挂卡）→ 退款归还回 IN_STOCK 或作废 VOID。 */
public class PoolItems {

    public record Item(long id, long supplierId, long cardProductId, String form, byte[] iccidCipher, byte[] iccidHash, String iccidLast4,
            byte[] credentialCipher, int keyVersion, String status, Long cardAssetId) {

        public Sealed sealedIccid() {
            return new Sealed(keyVersion, iccidCipher);
        }

        public Optional<Sealed> sealedCredential() {
            return credentialCipher == null ? Optional.empty() : Optional.of(new Sealed(keyVersion, credentialCipher));
        }
    }

    private static final String SELECT = """
            SELECT id, supplier_id, card_product_id, form, iccid_cipher, iccid_hash, iccid_last4, credential_cipher, key_version, status, card_asset_id
              FROM lr_card_pool_item
            """;

    private final JdbcClient jdbc;

    public PoolItems(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(byte[] iccidHash) {
        return jdbc.sql("SELECT COUNT(*) FROM lr_card_pool_item WHERE iccid_hash = ?").param(iccidHash).query(Integer.class).single() > 0;
    }

    /** 密文与卡号使用同一密钥版本（{@code key_version} 一列）。 */
    public void insert(long supplierId, long cardProductId, String form, Sealed iccid, byte[] iccidHash, String last4, byte[] credentialCipher,
            String batchNo) {
        jdbc.sql("""
                INSERT INTO lr_card_pool_item (supplier_id, card_product_id, form, iccid_cipher, iccid_hash, iccid_last4, credential_cipher, key_version,
                                               status, batch_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'IN_STOCK', ?)
                """).params(supplierId, cardProductId, form, iccid.bytes(), iccidHash, last4, credentialCipher, iccid.keyVersion(), batchNo).update();
    }

    /** 事务内取一张可分配卡并锁住；并发分配互相跳过。 */
    public Optional<Item> lockAvailable(long supplierId, long cardProductId, String form) {
        return jdbc.sql(SELECT + """
                 WHERE supplier_id = ? AND card_product_id = ? AND form = ? AND status = 'IN_STOCK'
                 ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).params(supplierId, cardProductId, form).query(Item.class).optional();
    }

    public Optional<Item> byCard(long cardAssetId) {
        return jdbc.sql(SELECT + " WHERE card_asset_id = ? AND status = 'ALLOCATED'").param(cardAssetId).query(Item.class).optional();
    }

    public Optional<Item> byIccidHash(byte[] hash) {
        return jdbc.sql(SELECT + " WHERE iccid_hash = ?").param(hash).query(Item.class).optional();
    }

    public void allocate(long id, long cardAssetId) {
        jdbc.sql("UPDATE lr_card_pool_item SET status = 'ALLOCATED', card_asset_id = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?")
                .params(cardAssetId, id).update();
    }

    public void returnToStock(long id) {
        jdbc.sql("UPDATE lr_card_pool_item SET status = 'IN_STOCK', card_asset_id = NULL, version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?")
                .param(id).update();
    }

    public void voidItem(long id) {
        jdbc.sql("UPDATE lr_card_pool_item SET status = 'VOID', version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?").param(id).update();
    }

    /** 交码导入命中尚未分配的池卡：预置交码内容，分配开通后按 IMPORT 通道交付。两份密文同一密钥版本一起重写。 */
    public void preloadCredential(long id, Sealed iccid, Sealed credential) {
        if (iccid.keyVersion() != credential.keyVersion()) {
            throw new IllegalArgumentException("卡号与交码密文须同一密钥版本");
        }
        jdbc.sql("""
                UPDATE lr_card_pool_item SET iccid_cipher = ?, credential_cipher = ?, key_version = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3)
                 WHERE id = ?
                """).params(iccid.bytes(), credential.bytes(), credential.keyVersion(), id).update();
    }
}
