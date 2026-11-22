package com.liroam.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.liroam.schema.testing.ItSchema;

import cn.lili.common.utils.SnowFlake;

/**
 * 自有表里引用壳 ID / 单号的列（{@code li_*}、{@code *member_id}）一律 {@code VARCHAR(64)}；
 * 壳的 ID 是 MyBatis-Plus 雪花 Long（≤19 位），单号 = 短前缀 + 雪花（{@code SnowFlake.createStr}）。
 * 两边任一侧变了（列被改窄、壳换了 ID 生成方式）这里就红。
 */
class ShellReferenceLengthTest {

    private static final int REFERENCE_COLUMN_LENGTH = 64;

    @Test
    void shellIdsAndOrderNumbersFitTheReferenceColumns() {
        int longestId = String.valueOf(Long.MAX_VALUE).length();
        List<String> generated = IntStream.range(0, 200).boxed()
                .flatMap(i -> List.of(SnowFlake.getIdStr(), SnowFlake.createStr("O"), SnowFlake.createStr("OI"), SnowFlake.createStr("SF")).stream())
                .toList();

        assertThat(generated).allSatisfy(s -> assertThat(s.length()).isLessThanOrEqualTo(longestId + 2));
        assertThat(longestId + 2).isLessThanOrEqualTo(REFERENCE_COLUMN_LENGTH);
    }

    @Test
    void everyShellReferenceColumnIsVarchar64() {
        DataSource ds = ItSchema.migrated("shell_ref_len");
        List<Map<String, Object>> columns = new JdbcTemplate(ds).queryForList("""
                SELECT table_name AS t, column_name AS c, data_type AS type, character_maximum_length AS len
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name LIKE 'lr\\_%'
                   AND (column_name LIKE 'li\\_%' OR column_name LIKE '%member\\_id')
                """);

        assertThat(columns).isNotEmpty();
        assertThat(columns).allSatisfy(col -> {
            assertThat(col.get("type")).as(col.get("t") + "." + col.get("c")).isEqualTo("varchar");
            assertThat(((Number) col.get("len")).intValue()).as(col.get("t") + "." + col.get("c")).isEqualTo(REFERENCE_COLUMN_LENGTH);
        });
    }
}
