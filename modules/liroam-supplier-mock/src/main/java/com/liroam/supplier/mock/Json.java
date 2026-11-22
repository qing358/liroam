package com.liroam.supplier.mock;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 拼模拟上游的原始响应（入 {@code raw_json} JSON 列）；值只有字符串、数字、null、嵌套对象与数组。 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(e -> string(e.getKey().toString()) + ":" + write(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Json::write).collect(Collectors.joining(",", "[", "]"));
        }
        return string(value.toString());
    }

    private static String string(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
