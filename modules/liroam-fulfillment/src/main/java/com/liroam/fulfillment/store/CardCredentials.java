package com.liroam.fulfillment.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.common.crypto.SensitiveCipher.Sealed;
import com.liroam.common.time.UtcColumns;

/** 交码（{@code lr_card_credential}）：三通道同一行结构；重交时旧行 {@code superseded_by} 指向新行。 */
public class CardCredentials {

    public record Credential(long id, long cardAssetId, String kind, byte[] payloadCipher, Integer keyVersion, String channel,
            String logisticsRef, String logisticsCompany, String logisticsNo, LocalDateTime deliveredAt, String operatorId) {

        public Optional<Sealed> sealedPayload() {
            return payloadCipher == null ? Optional.empty() : Optional.of(new Sealed(keyVersion, payloadCipher));
        }
    }

    private final JdbcClient jdbc;

    public CardCredentials(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long cardAssetId, String kind, Sealed payload, String channel, String operatorId, Instant deliveredAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_card_credential (card_asset_id, kind, payload_cipher, key_version, channel, delivered_at, operator_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(cardAssetId, kind, payload.bytes(), payload.keyVersion(), channel, UtcColumns.toColumn(deliveredAt), operatorId)
                .update(keys);
        return supersedeOthers(cardAssetId, keys.getKey().longValue());
    }

    /** 实体卡交码：只记物流，无密文。 */
    public long insertShipment(long cardAssetId, String logisticsRef, String logisticsCompany, String logisticsNo, String channel,
            String operatorId, Instant deliveredAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_card_credential (card_asset_id, kind, channel, logistics_ref, logistics_company, logistics_no, delivered_at, operator_id)
                VALUES (?, 'SHIPMENT', ?, ?, ?, ?, ?, ?)
                """).params(cardAssetId, channel, logisticsRef, logisticsCompany, logisticsNo, UtcColumns.toColumn(deliveredAt), operatorId)
                .update(keys);
        return supersedeOthers(cardAssetId, keys.getKey().longValue());
    }

    private long supersedeOthers(long cardAssetId, long id) {
        jdbc.sql("UPDATE lr_card_credential SET superseded_by = ? WHERE card_asset_id = ? AND id <> ? AND superseded_by IS NULL")
                .params(id, cardAssetId, id).update();
        return id;
    }

    public Optional<Credential> current(long cardAssetId) {
        return jdbc.sql("""
                SELECT id, card_asset_id, kind, payload_cipher, key_version, channel, logistics_ref, logistics_company, logistics_no, delivered_at,
                       operator_id
                  FROM lr_card_credential WHERE card_asset_id = ? AND superseded_by IS NULL ORDER BY id DESC LIMIT 1
                """).param(cardAssetId).query(Credential.class).optional();
    }
}
