package com.liroam.pricing.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 按样例资费表的版式（A–D 元数据、F 列起资源块、第 3 行「资源=」、第 5 行表头）生成小表，测边界分叉用。
 * 结构依据 docs/media 样例的实测：流量块列为 区域/国家/电信运营商/报价/GB[/备注]，块间空一列。
 */
public final class TestRateSheet {

    private final Map<String, List<String[]>> dataBlocks = new LinkedHashMap<>();
    private final Map<String, Boolean> remarkColumn = new LinkedHashMap<>();
    private final List<String[]> voice = new ArrayList<>();
    private String voiceResource;
    private final List<String[]> cardFees = new ArrayList<>();
    private String notes;
    private String currency = "港币";
    private String effective = "01-Nov-2026";

    /** 行：区域, 国家, 运营商, 报价[, 备注]。 */
    public TestRateSheet data(String resource, boolean withRemark, String[]... rows) {
        dataBlocks.put(resource, List.of(rows));
        remarkColumn.put(resource, withRemark);
        return this;
    }

    /** 行：国家, 运营商, 方向, 报价。 */
    public TestRateSheet voice(String resource, String[]... rows) {
        voiceResource = resource;
        voice.addAll(List.of(rows));
        return this;
    }

    public TestRateSheet cardFee(String type, String price) {
        cardFees.add(new String[] {type, price});
        return this;
    }

    public TestRateSheet notes(String text) {
        notes = text;
        return this;
    }

    public TestRateSheet currency(String text) {
        currency = text;
        return this;
    }

    public TestRateSheet effective(String text) {
        effective = text;
        return this;
    }

    public static String[] row(String... cells) {
        return cells;
    }

    public byte[] build() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet data = wb.createSheet("流量资费报价");
            meta(data);
            set(data, 0, 5, "流量资费报价");
            int col = 5;
            for (Map.Entry<String, List<String[]>> block : dataBlocks.entrySet()) {
                boolean remark = remarkColumn.get(block.getKey());
                set(data, 2, col, "资源= " + block.getKey());
                String[] header = remark ? new String[] {"区域", "国家", "电信运营商", "报价/GB", "备注"} : new String[] {"区域", "国家", "电信运营商", "报价/GB"};
                for (int i = 0; i < header.length; i++) {
                    set(data, 4, col + i, header[i]);
                }
                int r = 5;
                for (String[] cells : block.getValue()) {
                    for (int i = 0; i < cells.length; i++) {
                        set(data, r, col + i, cells[i]);
                    }
                    r++;
                }
                col += header.length + 1;
            }
            if (notes != null) {
                set(data, 32, 0, "注意事项:");
                set(data, 35, 0, notes);
            }
            if (voiceResource != null) {
                Sheet v = wb.createSheet("语音资费报价");
                meta(v);
                set(v, 2, 5, "資源 =" + voiceResource);
                String[] header = {"国家", "电信运营商", "语音拨打方向", "报价"};
                for (int i = 0; i < header.length; i++) {
                    set(v, 4, 5 + i, header[i]);
                }
                int r = 5;
                for (String[] cells : voice) {
                    for (int i = 0; i < cells.length; i++) {
                        set(v, r, 5 + i, cells[i]);
                    }
                    r++;
                }
            }
            if (!cardFees.isEmpty()) {
                Sheet c = wb.createSheet("卡资费报价");
                set(c, 4, 5, "卡类型");
                set(c, 4, 6, "报价");
                int r = 5;
                for (String[] fee : cardFees) {
                    set(c, r, 5, fee[0]);
                    set(c, r, 6, fee[1]);
                    r++;
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void meta(Sheet s) {
        set(s, 17, 0, "资费表生效日期:");
        set(s, 18, 0, effective);
        set(s, 20, 0, "计费币种:");
        set(s, 21, 0, currency);
    }

    private static void set(Sheet s, int row, int col, String value) {
        if (value == null) {
            return;
        }
        Row r = s.getRow(row) == null ? s.createRow(row) : s.getRow(row);
        if (value.matches("-?\\d+(\\.\\d+)?")) {
            r.createCell(col).setCellValue(Double.parseDouble(value));
        } else {
            r.createCell(col).setCellValue(value);
        }
    }
}
