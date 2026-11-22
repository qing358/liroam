package com.liroam.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SensitiveCipherTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static Map<String, String> env(String... kv) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return env;
    }

    private final Map<String, String> v1Env = env("LIROAM_DATA_KEY_V1", randomKey(), "LIROAM_DATA_HASH_KEY", randomKey());

    @Test
    void sealsWithCurrentKeyAndOpensBack() {
        SensitiveCipher cipher = SensitiveCipher.fromEnvironment(v1Env::get);

        SensitiveCipher.Sealed sealed = cipher.seal(SensitiveField.ICCID, "8985200000000000001");

        assertThat(sealed.keyVersion()).isEqualTo(1);
        assertThat(new String(sealed.bytes())).doesNotContain("8985200000000000001");
        assertThat(cipher.open(SensitiveField.ICCID, sealed)).isEqualTo("8985200000000000001");
    }

    @Test
    void sameValueSealsDifferentlyEachTime() {
        SensitiveCipher cipher = SensitiveCipher.fromEnvironment(v1Env::get);

        assertThat(cipher.seal(SensitiveField.ICCID, "8985200000000000001").bytes())
                .isNotEqualTo(cipher.seal(SensitiveField.ICCID, "8985200000000000001").bytes());
    }

    @Test
    void tamperedCiphertextIsRejected() {
        SensitiveCipher cipher = SensitiveCipher.fromEnvironment(v1Env::get);
        SensitiveCipher.Sealed sealed = cipher.seal(SensitiveField.ICCID, "8985200000000000001");
        byte[] bytes = sealed.bytes().clone();
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.open(SensitiveField.ICCID, new SensitiveCipher.Sealed(1, bytes)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ciphertextOfOneFieldCannotBeOpenedAsAnother() {
        SensitiveCipher cipher = SensitiveCipher.fromEnvironment(v1Env::get);
        SensitiveCipher.Sealed sealed = cipher.seal(SensitiveField.ICCID, "8985200000000000001");

        assertThatThrownBy(() -> cipher.open(SensitiveField.CREDENTIAL, sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotatedKeyStillOpensOldVersionAndSealsWithNewest() {
        Map<String, String> rotated = new HashMap<>(v1Env);
        SensitiveCipher.Sealed old = SensitiveCipher.fromEnvironment(v1Env::get).seal(SensitiveField.ICCID, "8985200000000000001");
        rotated.put("LIROAM_DATA_KEY_V2", randomKey());

        SensitiveCipher cipher = SensitiveCipher.fromEnvironment(rotated::get);

        assertThat(cipher.open(SensitiveField.ICCID, old)).isEqualTo("8985200000000000001");
        assertThat(cipher.seal(SensitiveField.ICCID, "x").keyVersion()).isEqualTo(2);
    }

    @Test
    void keyRingReportsCurrentAndAvailableVersions() {
        Map<String, String> rotated = new HashMap<>(v1Env);
        rotated.put("LIROAM_DATA_KEY_V3", randomKey());

        SensitiveCipher cipher = SensitiveCipher.fromEnvironment(rotated::get);

        assertThat(cipher.currentVersion()).isEqualTo(3);
        assertThat(cipher.versions()).containsExactly(1, 3);
    }

    @Test
    void oldVersionCanBeRetiredOnceDataIsResealed() {
        Map<String, String> rotated = new HashMap<>(v1Env);
        rotated.put("LIROAM_DATA_KEY_V2", randomKey());
        SensitiveCipher.Sealed resealed = SensitiveCipher.fromEnvironment(rotated::get).seal(SensitiveField.ICCID, "8985200000000000001");
        SensitiveCipher.Sealed old = SensitiveCipher.fromEnvironment(v1Env::get).seal(SensitiveField.ICCID, "8985200000000000001");
        rotated.remove("LIROAM_DATA_KEY_V1");

        SensitiveCipher v2Only = SensitiveCipher.fromEnvironment(rotated::get);

        assertThat(v2Only.versions()).containsExactly(2);
        assertThat(v2Only.open(SensitiveField.ICCID, resealed)).isEqualTo("8985200000000000001");
        assertThatThrownBy(() -> v2Only.open(SensitiveField.ICCID, old)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIROAM_DATA_KEY_V1");
    }

    @Test
    void hashIsStableAcrossKeyRotationAndSeparatedByField() {
        Map<String, String> rotated = new HashMap<>(v1Env);
        rotated.put("LIROAM_DATA_KEY_V2", randomKey());
        SensitiveCipher before = SensitiveCipher.fromEnvironment(v1Env::get);
        SensitiveCipher after = SensitiveCipher.fromEnvironment(rotated::get);

        assertThat(before.hash(SensitiveField.ICCID, "8985200000000000001"))
                .hasSize(32)
                .isEqualTo(after.hash(SensitiveField.ICCID, "8985200000000000001"))
                .isNotEqualTo(before.hash(SensitiveField.PHONE, "8985200000000000001"));
    }

    @Test
    void missingOrMalformedKeysFailWithoutLeakingValues() {
        assertThatThrownBy(() -> SensitiveCipher.fromEnvironment(env("LIROAM_DATA_HASH_KEY", randomKey())::get))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LIROAM_DATA_KEY_V1");
        assertThatThrownBy(() -> SensitiveCipher.fromEnvironment(env("LIROAM_DATA_KEY_V1", randomKey())::get))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LIROAM_DATA_HASH_KEY");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> SensitiveCipher.fromEnvironment(env("LIROAM_DATA_KEY_V1", shortKey, "LIROAM_DATA_HASH_KEY", randomKey())::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIROAM_DATA_KEY_V1")
                .hasMessageNotContaining(shortKey);
    }

    @Test
    void iccidIsNormalizedBeforeHashingAndMasking() {
        assertThat(SensitiveCipher.normalizeIccid(" 8985 2000-0000 0000 001f ")).isEqualTo("8985200000000000001F");
        assertThat(SensitiveCipher.last4("8985200000000000001F")).isEqualTo("001F");
        assertThatThrownBy(() -> SensitiveCipher.normalizeIccid("89#52")).isInstanceOf(IllegalArgumentException.class);
    }
}
