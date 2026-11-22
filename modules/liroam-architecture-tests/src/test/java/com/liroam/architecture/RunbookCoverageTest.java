package com.liroam.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 每个告警码都要在 {@code docs/ops-runbook.md} 里有处置条目（以 {@code `CODE`} 出现），值班拿到告警能直接查。 */
class RunbookCoverageTest {

    private static final Pattern ALERT_CODE = Pattern.compile("new OpsAlert\\(\\s*\"([A-Z][A-Z0-9_]+)\"");

    static SortedSet<String> alertCodes(Path modulesDir) {
        SortedSet<String> codes = new TreeSet<>();
        try (Stream<Path> files = Files.walk(modulesDir)) {
            files.filter(f -> f.toString().endsWith(".java") && f.toString().contains("/src/main/")).forEach(f -> {
                Matcher m = ALERT_CODE.matcher(read(f));
                while (m.find()) {
                    codes.add(m.group(1));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return codes;
    }

    static List<String> missing(Path modulesDir, Path runbook) {
        String text = Files.exists(runbook) ? read(runbook) : "";
        return alertCodes(modulesDir).stream().filter(code -> !text.contains("`" + code + "`")).toList();
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("deploy/ports.env"))) {
                return p;
            }
        }
        throw new IllegalStateException("找不到仓根");
    }

    @Test
    void everyAlertCodeHasARunbookEntry() {
        Path root = repoRoot();
        assertThat(alertCodes(root.resolve("modules"))).as("至少能扫到已知告警码").contains("COMMAND_MANUAL", "DB_CAPACITY", "DATA_KEY_STALE");
        assertThat(missing(root.resolve("modules"), root.resolve("docs/ops-runbook.md"))).as("docs/ops-runbook.md 缺这些告警码的处置").isEmpty();
    }

    @Test
    void scanFindsCodesAcrossLinesAndIgnoresTests(@TempDir Path dir) throws IOException {
        Path main = dir.resolve("liroam-x/src/main/java/A.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, "a.add(new OpsAlert(\"FOO_BAR\", L.WARN,\n\"t\")); b.add(new OpsAlert(\n  \"BAZ\", L.CRITICAL));");
        Path test = dir.resolve("liroam-x/src/test/java/T.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "new OpsAlert(\"ONLY_IN_TEST\", L.WARN)");
        Path runbook = dir.resolve("runbook.md");
        Files.writeString(runbook, "### `FOO_BAR` 处置\nBAZ 没加反引号不算");

        assertThat(alertCodes(dir)).containsExactly("BAZ", "FOO_BAR");
        assertThat(missing(dir, runbook)).containsExactly("BAZ");
    }
}
