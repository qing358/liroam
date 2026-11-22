package com.liroam.common.testing;

import java.security.SecureRandom;
import java.util.Base64;

/** 每次运行现生成的测试数据密钥，不落盘。 */
public final class TestKeys {

    private TestKeys() {
    }

    public static String[] properties() {
        return new String[] {"LIROAM_DATA_KEY_V1=" + random(), "LIROAM_DATA_HASH_KEY=" + random()};
    }

    private static String random() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
