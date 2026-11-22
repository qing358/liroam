package com.liroam.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.ops.OpsAlert;
import com.liroam.common.web.UserFacingException;
import com.liroam.ops.keys.DataKeyRotation;
import com.liroam.ops.keys.DataKeyRotation.Status;
import com.liroam.schema.SealedTables;

/**
 * 数据密钥轮换：新版本密钥上线后，旧版本加密的行按批重加密到新版本（审计），之后旧版本可下线；
 * 有行仍在旧版本 → 注意级告警；有行的版本已不在密钥环 → 严重告警且重加密跳过它们。
 */
class DataKeyRotationTest {

    private static OpsFixture f;
    private static String v1;

    @BeforeAll
    static void start() {
        f = OpsFixture.start("data_keys");
        v1 = f.bean(Environment.class).getProperty("LIROAM_DATA_KEY_V1");
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static SensitiveCipher ring(Map<String, String> keys) {
        Map<String, String> env = new HashMap<>(keys);
        env.put("LIROAM_DATA_HASH_KEY", f.bean(Environment.class).getProperty("LIROAM_DATA_HASH_KEY"));
        return SensitiveCipher.fromEnvironment(env::get);
    }

    private static DataKeyRotation rotation(SensitiveCipher cipher) {
        return new DataKeyRotation(f.bean(JdbcClient.class), cipher, SealedTables.ALL, f.bean(AuditLog.class));
    }

    private long rowsAt(String table, int version) {
        return f.h.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE key_version = ?", Long.class, version);
    }

    @Test
    void resealsOldVersionRowsInBatchesAndOldKeyCanThenBeRetired() {
        f.deliveredCard("O-1", "m1");
        assertThat(f.h.count("lr_card_credential")).isPositive();
        long sealedRows = rowsAt("lr_card_asset", 1) + rowsAt("lr_card_pool_item", 1) + rowsAt("lr_card_credential", 1);
        assertThat(sealedRows).isGreaterThanOrEqualTo(3);

        String v2 = randomKey();
        DataKeyRotation rotation = rotation(ring(Map.of("LIROAM_DATA_KEY_V1", v1, "LIROAM_DATA_KEY_V2", v2)));
        Status before = rotation.status();
        assertThat(before.currentVersion()).isEqualTo(2);
        assertThat(before.staleRows()).isEqualTo(sealedRows);
        assertThat(before.unreadableRows()).isZero();
        assertThat(before.tables()).extracting(DataKeyRotation.TableStatus::table)
                .containsExactlyElementsOf(SealedTables.ALL.stream().map(SealedTables.SealedTable::table).toList());
        assertThat(rotation.check(f.now())).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("DATA_KEY_STALE");
            assertThat(a.level()).isEqualTo(OpsAlert.Level.WARN);
            assertThat(a.count()).isEqualTo(sealedRows);
        });

        DataKeyRotation.ResealResult first = rotation.reseal(2, "manager:admin");
        assertThat(first.resealed()).isEqualTo(2);
        assertThat(first.remaining()).isEqualTo(sealedRows - 2);
        while (rotation.reseal(100, "manager:admin").remaining() > 0) {
            // 继续下一批
        }
        assertThat(rotation.status().staleRows()).isZero();
        assertThat(rotation.check(f.now())).isEmpty();
        assertThat(f.h.count("lr_audit_log WHERE action = 'DATA_KEY_RESEAL'")).isGreaterThanOrEqualTo(2);

        SensitiveCipher v2Only = ring(Map.of("LIROAM_DATA_KEY_V2", v2));
        Map<String, Object> cred = f.h.jdbc.queryForMap("SELECT payload_cipher, key_version FROM lr_card_credential LIMIT 1");
        assertThat(((Number) cred.get("key_version")).intValue()).isEqualTo(2);
        assertThat(v2Only.open(SensitiveField.CREDENTIAL, new SensitiveCipher.Sealed(2, (byte[]) cred.get("payload_cipher")))).startsWith("LPA:");
        Map<String, Object> pool = f.h.jdbc.queryForMap("SELECT iccid_cipher, key_version FROM lr_card_pool_item LIMIT 1");
        assertThat(v2Only.open(SensitiveField.ICCID, new SensitiveCipher.Sealed(2, (byte[]) pool.get("iccid_cipher")))).isNotBlank();
        assertThat(rotation(v2Only).status().unreadableRows()).isZero();
    }

    @Test
    void rowsOnAVersionMissingFromTheRingRaiseCriticalAlertAndAreSkipped() {
        f.deliveredCard("O-2", "m1");
        long rows = rowsAt("lr_card_asset", 1) + rowsAt("lr_card_pool_item", 1) + rowsAt("lr_card_credential", 1);
        DataKeyRotation withoutV1 = rotation(ring(Map.of("LIROAM_DATA_KEY_V2", randomKey())));

        assertThat(withoutV1.status().unreadableRows()).isEqualTo(rows);
        List<OpsAlert> alerts = withoutV1.check(f.now());
        assertThat(alerts).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("DATA_KEY_UNREADABLE");
            assertThat(a.level()).isEqualTo(OpsAlert.Level.CRITICAL);
            assertThat(a.detail()).contains("LIROAM_DATA_KEY_V1");
        });
        assertThat(withoutV1.reseal(100, "manager:admin").resealed()).isZero();
        assertThat(rowsAt("lr_card_credential", 1)).isPositive();
    }

    @Test
    void batchSizeIsBounded() {
        DataKeyRotation rotation = rotation(ring(Map.of("LIROAM_DATA_KEY_V1", v1)));
        assertThatThrownBy(() -> rotation.reseal(0, "op")).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> rotation.reseal(1001, "op")).isInstanceOf(UserFacingException.class);
        assertThat(rotation.reseal(10, "op").resealed()).isZero();
        assertThat(f.h.count("lr_audit_log WHERE action = 'DATA_KEY_RESEAL'")).as("没有重加密不记审计").isZero();
    }
}
