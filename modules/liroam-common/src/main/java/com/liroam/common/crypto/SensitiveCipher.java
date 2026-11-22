package com.liroam.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 敏感字段（ICCID、交码内容、手机号）的加密与查询哈希。
 * <p>
 * 加密：AES-256-GCM，密文 = 12 字节 IV ‖ 密文+标签，与 {@code key_version} 一起入库；密钥来自环境变量
 * {@code LIROAM_DATA_KEY_V1..V32}（Base64 的 32 字节，至少一个），新写入用最大版本，旧版本只用于解密；旧数据重加密到最大版本后旧版本可下线。
 * 查询：HMAC-SHA256，密钥 {@code LIROAM_DATA_HASH_KEY} 不随加密密钥轮换，保证 {@code *_hash} 唯一键稳定。
 */
public final class SensitiveCipher {

    public static final String DATA_KEY_PREFIX = "LIROAM_DATA_KEY_V";
    public static final String HASH_KEY = "LIROAM_DATA_HASH_KEY";
    private static final int MAX_VERSION = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    public record Sealed(int keyVersion, byte[] bytes) {
    }

    private final Map<Integer, SecretKey> dataKeys;
    private final int currentVersion;
    private final SecretKey hashKey;
    private final SecureRandom random = new SecureRandom();

    private SensitiveCipher(Map<Integer, SecretKey> dataKeys, SecretKey hashKey) {
        this.dataKeys = Map.copyOf(dataKeys);
        this.currentVersion = ((TreeMap<Integer, SecretKey>) dataKeys).lastKey();
        this.hashKey = hashKey;
    }

    public static SensitiveCipher fromEnvironment(Function<String, String> env) {
        TreeMap<Integer, SecretKey> keys = new TreeMap<>();
        for (int v = 1; v <= MAX_VERSION; v++) {
            String raw = env.apply(DATA_KEY_PREFIX + v);
            if (raw != null && !raw.isBlank()) {
                keys.put(v, decodeKey(DATA_KEY_PREFIX + v, raw, "AES"));
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("缺少数据加密密钥环境变量 " + DATA_KEY_PREFIX + "1（轮换后为最大版本 " + DATA_KEY_PREFIX + "N）");
        }
        String hash = env.apply(HASH_KEY);
        if (hash == null || hash.isBlank()) {
            throw new IllegalStateException("缺少查询哈希密钥环境变量 " + HASH_KEY);
        }
        return new SensitiveCipher(keys, decodeKey(HASH_KEY, hash, "HmacSHA256"));
    }

    private static SecretKey decodeKey(String name, String raw, String algorithm) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " 不是合法的 Base64");
        }
        if (bytes.length != 32) {
            throw new IllegalStateException(name + " 须为 32 字节（Base64 编码），实际 " + bytes.length + " 字节");
        }
        return new SecretKeySpec(bytes, algorithm);
    }

    /** 新写入用的版本（已配置的最大版本）。 */
    public int currentVersion() {
        return currentVersion;
    }

    /** 已配置、可解密的全部版本，升序。 */
    public SortedSet<Integer> versions() {
        return Collections.unmodifiableSortedSet(new TreeSet<>(dataKeys.keySet()));
    }

    public Sealed seal(SensitiveField field, String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dataKeys.get(currentVersion), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(field.name().getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Sealed(currentVersion, ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密 " + field + " 失败", e);
        }
    }

    public String open(SensitiveField field, Sealed sealed) {
        SecretKey key = dataKeys.get(sealed.keyVersion());
        if (key == null) {
            throw new IllegalStateException("缺少密钥版本 " + DATA_KEY_PREFIX + sealed.keyVersion());
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.bytes(), 0, IV_BYTES));
            cipher.updateAAD(field.name().getBytes(StandardCharsets.UTF_8));
            byte[] pt = cipher.doFinal(sealed.bytes(), IV_BYTES, sealed.bytes().length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("解密 " + field + " 失败（密文被改动、字段不符或密钥不对）");
        }
    }

    /** 32 字节，入库列 {@code BINARY(32)}；调用方先规范化取值（如 {@link #normalizeIccid}）。 */
    public byte[] hash(SensitiveField field, String normalizedValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hashKey);
            mac.update(field.name().getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(normalizedValue.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("计算 " + field + " 哈希失败", e);
        }
    }

    public static String normalizeIccid(String raw) {
        String value = raw.replaceAll("[\\s-]", "").toUpperCase();
        if (!value.matches("[0-9A-Z]{4,32}")) {
            throw new IllegalArgumentException("ICCID 只能包含数字与字母");
        }
        return value;
    }

    public static String last4(String normalized) {
        return normalized.substring(Math.max(0, normalized.length() - 4));
    }
}
