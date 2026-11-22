package com.liroam.pricing.tariff;

import java.util.Map;

/** 资源码前 3 位是归属网络的 MCC（如 45412_C → 454 香港）；认不出记 {@code ZZ}，由运营在卡产品上改。 */
final class HomeLocation {

    static final String UNKNOWN = "ZZ";

    private static final Map<String, String> BY_MCC = Map.of("454", "HK", "455", "MO", "460", "CN", "466", "TW");

    private HomeLocation() {
    }

    static String ofResource(String resourceCode) {
        return resourceCode.length() >= 3 ? BY_MCC.getOrDefault(resourceCode.substring(0, 3), UNKNOWN) : UNKNOWN;
    }
}
