package com.liroam.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 源码里的厂商/渠道名只允许出现在以其命名的适配器模块（如 {@code modules/liroam-supplier-quadcell}）。
 * 业务代码一律用领域代号（SUP_A…）。新接入一家时在 {@link #VENDOR_TOKENS} 登记其名字。
 */
class VendorNameScanTest {

    /** 模块目录名关键字 → 需拦截的写法。 */
    static final Map<String, List<String>> VENDOR_TOKENS = Map.of(
            "quadcell", List.of("quadcell"),
            "jd", List.of("jingdong", "京东", "jd.com"),
            "xhs", List.of("xiaohongshu", "小红书"));

    static List<String> violations(Path modulesDir) {
        try (Stream<Path> modules = Files.list(modulesDir)) {
            return modules.filter(Files::isDirectory).flatMap(module -> {
                Path main = module.resolve("src/main");
                if (!Files.isDirectory(main)) {
                    return Stream.empty();
                }
                String moduleName = module.getFileName().toString().toLowerCase(Locale.ROOT);
                try (Stream<Path> files = Files.walk(main)) {
                    return files.filter(Files::isRegularFile).flatMap(file -> {
                        String text = read(file).toLowerCase(Locale.ROOT);
                        return VENDOR_TOKENS.entrySet().stream()
                                .filter(v -> !moduleName.contains(v.getKey()))
                                .flatMap(v -> v.getValue().stream().filter(text::contains))
                                .map(token -> modulesDir.relativize(file) + " 含 " + token);
                    }).toList().stream();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path modulesDir() {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("deploy/ports.env"))) {
                return p.resolve("modules");
            }
        }
        throw new IllegalStateException("找不到仓根");
    }

    @Test
    void productionSourcesUseDomainCodesOnly() {
        assertThat(violations(modulesDir())).isEmpty();
    }

    @Test
    void scanCatchesVendorNameOutsideItsAdapterModule(@TempDir Path dir) throws IOException {
        Path leak = dir.resolve("liroam-catalog/src/main/java/X.java");
        Files.createDirectories(leak.getParent());
        Files.writeString(leak, "class X { String s = \"QuadCell\"; }");
        Path allowed = dir.resolve("liroam-supplier-quadcell/src/main/java/Y.java");
        Files.createDirectories(allowed.getParent());
        Files.writeString(allowed, "class Y { String s = \"quadcell\"; }");
        Path channel = dir.resolve("liroam-fulfillment/src/main/resources/a.sql");
        Files.createDirectories(channel.getParent());
        Files.writeString(channel, "-- 京东渠道");

        assertThat(violations(dir)).hasSize(2).anyMatch(v -> v.contains("quadcell")).anyMatch(v -> v.contains("京东"));
    }
}
