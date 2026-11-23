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
 * 面向用户的文案点名售卖渠道不算业务分支，逐文件登记在 {@link #USER_COPY_FILES}。
 */
class VendorNameScanTest {

    /** 模块目录名关键字 → 需拦截的写法。 */
    static final Map<String, List<String>> VENDOR_TOKENS = Map.of(
            "quadcell", List.of("quadcell"),
            "jd", List.of("jingdong", "京东", "jd.com"),
            "xhs", List.of("xiaohongshu", "小红书"));

    /** 相对 modules/ 的路径 → 放行理由。只放已落库、不能再改内容的文案数据，不放代码。 */
    static final Map<String, String> USER_COPY_FILES = Map.of(
            "liroam-schema/src/main/resources/db/migration/liroam/V202611261100__brand_liroam_copy.sql",
            "关于我们文章正文，与买家端登录/注册页同一句渠道认领文案；已在库执行，改内容会使 Flyway 校验和失配");

    static List<String> violations(Path modulesDir) {
        try (Stream<Path> modules = Files.list(modulesDir)) {
            return modules.filter(Files::isDirectory).flatMap(module -> {
                Path main = module.resolve("src/main");
                if (!Files.isDirectory(main)) {
                    return Stream.empty();
                }
                String moduleName = module.getFileName().toString().toLowerCase(Locale.ROOT);
                try (Stream<Path> files = Files.walk(main)) {
                    return files.filter(Files::isRegularFile)
                            .filter(file -> !USER_COPY_FILES.containsKey(relative(modulesDir, file)))
                            .flatMap(file -> {
                        String text = read(file).toLowerCase(Locale.ROOT);
                        return VENDOR_TOKENS.entrySet().stream()
                                .filter(v -> !moduleName.contains(v.getKey()))
                                .flatMap(v -> v.getValue().stream().filter(text::contains))
                                .map(token -> relative(modulesDir, file) + " 含 " + token);
                    }).toList().stream();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path modulesDir, Path file) {
        return modulesDir.relativize(file).toString().replace('\\', '/');
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

    @Test
    void userCopyAllowanceCoversOnlyListedFiles(@TempDir Path dir) throws IOException {
        String listed = USER_COPY_FILES.keySet().iterator().next();
        Path copy = dir.resolve(listed);
        Files.createDirectories(copy.getParent());
        Files.writeString(copy, "-- 京东、小红书");
        Path sibling = copy.resolveSibling("V999__other_copy.sql");
        Files.writeString(sibling, "-- 小红书");

        assertThat(violations(dir)).containsExactly(relative(dir, sibling) + " 含 小红书");
    }

    @Test
    void userCopyAllowanceHasNoStaleEntries() {
        Path modules = modulesDir();
        assertThat(USER_COPY_FILES).allSatisfy((path, reason) -> {
            assertThat(reason).isNotBlank();
            String text = read(modules.resolve(path)).toLowerCase(Locale.ROOT);
            assertThat(VENDOR_TOKENS.values().stream().flatMap(List::stream).anyMatch(text::contains))
                    .as("%s 已不含厂商/渠道名，应从放行表删除", path).isTrue();
        });
    }
}
