package com.liroam.pricing.region;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 资费表中文国家/地区名 → ISO 码的唯一映射（资源文件 {@code regions-zh.txt}）。
 * 也用于在注意事项原文里找出提到的地区（最长名优先，避免「印度」误中「印度尼西亚」）。
 */
public final class RegionDictionary {

    static final String RESOURCE = "regions-zh.txt";

    public record KnownRegion(String code, String name) {
    }

    private final Map<String, KnownRegion> byName;
    private final List<String> namesLongestFirst;

    private RegionDictionary(Map<String, KnownRegion> byName) {
        this.byName = Map.copyOf(byName);
        this.namesLongestFirst = byName.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    public static RegionDictionary load() {
        Map<String, KnownRegion> byName = new HashMap<>();
        try (InputStream in = RegionDictionary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("缺少资源 " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null;) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String[] parts = t.split("\\|", -1);
                if (parts.length != 3) {
                    throw new IllegalStateException(RESOURCE + " 行格式应为 代码|规范名|别名：" + t);
                }
                KnownRegion region = new KnownRegion(parts[0].strip(), parts[1].strip());
                put(byName, region.name(), region);
                for (String alias : parts[2].split(",")) {
                    if (!alias.isBlank()) {
                        put(byName, alias.strip(), region);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new RegionDictionary(byName);
    }

    private static void put(Map<String, KnownRegion> byName, String name, KnownRegion region) {
        KnownRegion previous = byName.put(normalize(name), region);
        if (previous != null && !previous.equals(region)) {
            throw new IllegalStateException(RESOURCE + " 中「" + name + "」同时指向 " + previous.code() + " 与 " + region.code());
        }
    }

    public Optional<KnownRegion> match(String rawName) {
        return rawName == null ? Optional.empty() : Optional.ofNullable(byName.get(normalize(rawName)));
    }

    /** 原文里提到的地区代码。 */
    public Set<String> codesMentionedIn(String text) {
        Set<String> codes = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return codes;
        }
        StringBuilder masked = new StringBuilder(text);
        for (String name : namesLongestFirst) {
            for (int i = masked.indexOf(name); i >= 0; i = masked.indexOf(name, i + name.length())) {
                codes.add(byName.get(name).code());
                for (int k = i; k < i + name.length(); k++) {
                    masked.setCharAt(k, '\u0000');
                }
            }
        }
        return codes;
    }

    private static String normalize(String name) {
        return name.strip().replaceAll("\\s+", "");
    }
}
