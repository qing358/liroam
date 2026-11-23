package com.liroam.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 发布与回滚（§3.6）：版本迁移只增不改，上一版应用仍能跑在新库上。删列/删表/改名/改类型要拆两步（先加后删），
 * 删的那步单独评审，不走这条自动闸。
 */
class AdditiveMigrationsTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration/liroam");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\bDROP\\s+(TABLE|COLUMN|DATABASE|SCHEMA)\\b|\\bALTER\\s+TABLE\\s+\\w+\\s+DROP\\b|,\\s*DROP\\s+\\w+|\\bRENAME\\s+(TABLE|COLUMN|TO|INDEX|KEY)\\b"
                    + "|\\bMODIFY\\s+(COLUMN\\s+)?\\w+|\\bCHANGE\\s+(COLUMN\\s+)?\\w+\\s+\\w+|\\bTRUNCATE\\b",
            Pattern.CASE_INSENSITIVE);

    static List<String> violations(String fileName, String sql) {
        String code = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ").replaceAll("'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+'", "''");
        List<String> found = new ArrayList<>();
        Matcher m = FORBIDDEN.matcher(code);
        while (m.find()) {
            found.add(fileName + ": " + m.group().replaceAll("\\s+", " "));
        }
        return found;
    }

    @Test
    void versionedMigrationsOnlyAdd() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("V")).sorted().toList()) {
                found.addAll(violations(f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void detectorCatchesDestructiveDdlButNotCommentsOrStrings() {
        assertThat(violations("x", "ALTER TABLE lr_a DROP COLUMN b;")).isNotEmpty();
        assertThat(violations("x", "ALTER TABLE lr_a ADD COLUMN c INT, DROP KEY k;")).isNotEmpty();
        assertThat(violations("x", "ALTER TABLE lr_a MODIFY COLUMN b BIGINT;")).isNotEmpty();
        assertThat(violations("x", "ALTER TABLE lr_a CHANGE b c INT;")).isNotEmpty();
        assertThat(violations("x", "RENAME TABLE lr_a TO lr_b;")).isNotEmpty();
        assertThat(violations("x", "DROP TABLE lr_a;")).isNotEmpty();
        assertThat(violations("x", "TRUNCATE lr_a;")).isNotEmpty();
        assertThat(violations("x", "-- 以后 DROP TABLE lr_a\nALTER TABLE lr_a ADD COLUMN c VARCHAR(8) COMMENT 'MODIFY 前缀';")).isEmpty();
        assertThat(violations("x", "SET @page := '" + "{\\\"a\\\":\\\"b\\\"},".repeat(2000) + "';")).as("整页装修 JSON 这类长字面量").isEmpty();
    }
}
