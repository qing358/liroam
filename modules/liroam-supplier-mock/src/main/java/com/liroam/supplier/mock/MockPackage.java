package com.liroam.supplier.mock;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模拟上游认得的套餐码（即 {@code lr_supplier_offering.upstream_package_code}）：
 * {@code {GB}GB-{天数}D}，可选后缀 {@code -{分钟}MIN}、{@code -{条数}SMS}（只有计量语音短信的形状 B 会报出）。
 * 例：{@code 5GB-7D}、{@code 1GB-1D}、{@code 3GB-30D-100MIN-50SMS}。开卡与加包同一格式。
 */
record MockPackage(String code, long quotaBytes, int days, long voiceSeconds, int sms) {

    static final long GIB = 1024L * 1024L * 1024L;

    private static final Pattern FORMAT = Pattern.compile("(\\d{1,4})GB-(\\d{1,4})D(?:-(\\d{1,5})MIN)?(?:-(\\d{1,5})SMS)?");

    static Optional<MockPackage> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(code);
        if (!m.matches()) {
            return Optional.empty();
        }
        int gb = Integer.parseInt(m.group(1));
        int days = Integer.parseInt(m.group(2));
        if (gb <= 0 || days <= 0) {
            return Optional.empty();
        }
        long minutes = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        int sms = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        return Optional.of(new MockPackage(code, gb * GIB, days, minutes * 60, sms));
    }
}
