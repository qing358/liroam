package com.liroam.channel;

/** 手机号规范化：去空格、横线与括号，去掉 +86 / 0086 / 86 国家码，便于加密哈希比对。 */
final class Phones {

    private Phones() {
    }

    /** @return 规范化后的号码；不像手机号返回 null */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s\\-()]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.startsWith("0086")) {
            digits = digits.substring(4);
        } else if (digits.startsWith("86") && digits.length() == 13) {
            digits = digits.substring(2);
        }
        return digits.matches("\\d{6,15}") ? digits : null;
    }
}
