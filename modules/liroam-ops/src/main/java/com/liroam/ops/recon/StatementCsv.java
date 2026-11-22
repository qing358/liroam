package com.liroam.ops.recon;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.liroam.common.web.UserFacingException;

/**
 * 对账单 CSV：首行表头（列名不分大小写、顺序不限），逗号分隔、不支持引号转义（账单字段不含逗号）。金额按元，最多两位小数。
 * 解析失败整份拒绝并指出行号，不落半份数据。
 */
final class StatementCsv {

    record Row(int lineNo, Map<String, String> cells) {

        String get(String column) {
            return cells.getOrDefault(column, "").trim();
        }
    }

    private StatementCsv() {
    }

    static List<Row> parse(String csv, String... required) {
        if (csv == null || csv.isBlank()) {
            throw new UserFacingException("账单为空：首行须为表头 " + String.join(",", required));
        }
        String[] lines = csv.replace("\r", "").replace("\uFEFF", "").split("\n");
        List<String> header = Arrays.stream(lines[0].split(",")).map(h -> h.trim().toLowerCase()).toList();
        for (String col : required) {
            if (!header.contains(col)) {
                throw new UserFacingException("账单表头缺少列 " + col + "（须含 " + String.join(",", required) + "）");
            }
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = lines[i].split(",", -1);
            Map<String, String> m = new HashMap<>();
            for (int c = 0; c < header.size() && c < cells.length; c++) {
                m.put(header.get(c), cells[c]);
            }
            rows.add(new Row(i + 1, m));
        }
        return rows;
    }

    static long minor(Row row, String column) {
        String raw = row.get(column);
        try {
            BigDecimal v = new BigDecimal(raw);
            if (v.scale() > 2) {
                throw new NumberFormatException();
            }
            return v.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new UserFacingException("第 " + row.lineNo() + " 行金额「" + raw + "」不对：按元填写，最多两位小数");
        }
    }

    static UserFacingException bad(Row row, String message) {
        return new UserFacingException("第 " + row.lineNo() + " 行" + message);
    }
}
