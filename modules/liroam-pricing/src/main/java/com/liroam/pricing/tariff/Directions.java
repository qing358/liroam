package com.liroam.pricing.tariff;

import com.liroam.pricing.region.RegionDictionary;

/**
 * 语音/短信「方向」原文归一：{@code LOCAL}（打/发到所在国）、{@code INCOMING}（被叫）、{@code TO_<ISO>}（打/发到某地）、
 * {@code OTHER}（其他地区或认不出）。流量不分方向，为 {@code ANY}。
 */
final class Directions {

    private Directions() {
    }

    static String normalize(String kind, String raw, String regionCode, RegionDictionary dictionary) {
        if (RateSheet.Kind.DATA.name().equals(kind)) {
            return "ANY";
        }
        if (raw == null) {
            return "OTHER";
        }
        String text = raw.strip();
        if (text.contains("被叫")) {
            return "INCOMING";
        }
        String target = text.replaceFirst("^(拨打至|拨打到|短信发送到|短信发送至|发送到|发送至)", "");
        return dictionary.match(target).map(r -> r.code().equals(regionCode) ? "LOCAL" : "TO_" + r.code()).orElse("OTHER");
    }
}
