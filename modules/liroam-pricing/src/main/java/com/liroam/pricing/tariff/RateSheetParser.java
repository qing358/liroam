package com.liroam.pricing.tariff;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.liroam.common.web.UserFacingException;

/**
 * 供应商报价单 xlsx 版式（以 docs/media 样例实测为准）：
 * <ul>
 * <li>每个工作表 A 列是元数据，「标签:」下一格是值（生效日期、计费币种、注意事项）；</li>
 * <li>流量/语音/短信表 F 列起并排若干资源块，块首格「资源= 代码」，其下第一个非空行是表头；</li>
 * <li>表头按名字找「运营商 / 方向 / 报价 / 备注」列，国家在运营商左一列、大洲再左一列（短信表表头少标一列，数据仍如此排）；</li>
 * <li>卡资费表无资源块，「卡类型 / 报价」两列，非数字报价（如平台费说明）不入库。</li>
 * </ul>
 * 单价按表内显示格式 {@code 0.0000} 取 4 位小数：报价单以显示值为准。
 */
final class RateSheetParser {

    static final int PRICE_SCALE = 4;

    private static final Pattern RESOURCE = Pattern.compile("^[资資]源\\s*=\\s*(\\S+)$");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"));

    private RateSheetParser() {
    }

    static RateSheet parse(byte[] xlsx) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            return parse(wb);
        } catch (UserFacingException e) {
            throw e;
        } catch (Exception e) {
            throw new UserFacingException("文件不是有效的 xlsx 资费表，请上传供应商原始报价文件");
        }
    }

    private static RateSheet parse(Workbook wb) {
        Sheet data = sheet(wb, "流量");
        if (data == null) {
            throw new UserFacingException("资费表缺少「流量资费报价」工作表，无法识别版式");
        }
        String currency = metaValue(data, "计费币种");
        if (currency == null || !(currency.contains("港币") || currency.toUpperCase(Locale.ROOT).contains("HKD"))) {
            throw new UserFacingException("目前只支持港币计价的资费表，本表计费币种为：" + (currency == null ? "未标注" : currency));
        }
        LocalDate effective = effectiveDate(data);
        List<RateSheet.Line> lines = new ArrayList<>(blocks(data, RateSheet.Kind.DATA));
        if (lines.isEmpty()) {
            throw new UserFacingException("「流量资费报价」里没有找到「资源=」资源块或报价行，无法识别版式");
        }
        Sheet voice = sheet(wb, "语音");
        if (voice != null) {
            lines.addAll(blocks(voice, RateSheet.Kind.VOICE));
        }
        Sheet sms = sheet(wb, "短信");
        if (sms != null) {
            lines.addAll(blocks(sms, RateSheet.Kind.SMS));
        }
        Sheet card = sheet(wb, "卡资费");
        if (card != null) {
            lines.addAll(cardFees(card));
        }
        return new RateSheet(effective, currency, notes(data), lines);
    }

    private static Sheet sheet(Workbook wb, String nameContains) {
        for (Sheet s : wb) {
            if (s.getSheetName().contains(nameContains)) {
                return s;
            }
        }
        return null;
    }

    private static Cell metaCell(Sheet s, String label) {
        boolean found = false;
        for (Row row : s) {
            Cell cell = row.getCell(0);
            String text = text(cell);
            if (text == null) {
                continue;
            }
            if (found) {
                return cell;
            }
            found = text.startsWith(label);
        }
        return null;
    }

    private static String metaValue(Sheet s, String label) {
        return text(metaCell(s, label));
    }

    private static LocalDate effectiveDate(Sheet s) {
        Cell cell = metaCell(s, "资费表生效日期");
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
            return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate();
        }
        String text = text(cell);
        if (text != null) {
            for (DateTimeFormatter f : DATE_FORMATS) {
                try {
                    return LocalDate.parse(text, f);
                } catch (DateTimeParseException ignored) {
                    // 换下一种写法
                }
            }
        }
        throw new UserFacingException("资费表缺少可识别的「资费表生效日期」" + (text == null ? "" : "：" + text));
    }

    /** 「注意事项:」之后 A 列的全部文字。 */
    private static String notes(Sheet s) {
        StringBuilder sb = new StringBuilder();
        boolean found = false;
        for (Row row : s) {
            String text = text(row.getCell(0));
            if (text == null) {
                continue;
            }
            if (found) {
                sb.append(sb.isEmpty() ? "" : "\n\n").append(text);
            } else {
                found = text.startsWith("注意事项");
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private record Block(String resource, int start, int end) {
    }

    private static List<RateSheet.Line> blocks(Sheet s, RateSheet.Kind kind) {
        List<RateSheet.Line> lines = new ArrayList<>();
        for (Row row : s) {
            List<Block> blocks = resourceBlocks(row);
            if (blocks.isEmpty()) {
                continue;
            }
            for (Block block : blocks) {
                lines.addAll(blockLines(s, row.getRowNum(), block, kind));
            }
            break;
        }
        return lines;
    }

    /** 块的列范围到下一个块首为止（不含），最后一块到行尾。 */
    private static List<Block> resourceBlocks(Row row) {
        List<Block> blocks = new ArrayList<>();
        for (Cell cell : row) {
            String text = text(cell);
            Matcher m = text == null ? null : RESOURCE.matcher(text);
            if (m == null || !m.matches()) {
                continue;
            }
            if (!blocks.isEmpty()) {
                Block last = blocks.remove(blocks.size() - 1);
                blocks.add(new Block(last.resource(), last.start(), cell.getColumnIndex()));
            }
            blocks.add(new Block(m.group(1), cell.getColumnIndex(), Integer.MAX_VALUE));
        }
        return blocks;
    }

    private static List<RateSheet.Line> blockLines(Sheet s, int resourceRow, Block block, RateSheet.Kind kind) {
        int headerRow = -1;
        for (int r = resourceRow + 1; r <= s.getLastRowNum(); r++) {
            if (text(cell(s, r, block.start())) != null) {
                headerRow = r;
                break;
            }
        }
        if (headerRow < 0) {
            throw layout(s, block, "找不到表头");
        }
        int carrier = -1;
        int price = -1;
        int direction = -1;
        int remark = -1;
        Row header = s.getRow(headerRow);
        for (Cell cell : header) {
            int c = cell.getColumnIndex();
            String name = text(cell);
            if (c < block.start() || c >= block.end() || name == null) {
                continue;
            }
            if (name.contains("运营商") && carrier < 0) {
                carrier = c;
            } else if (name.contains("报价") && price < 0) {
                price = c;
            } else if (name.contains("方向") && direction < 0) {
                direction = c;
            } else if (name.contains("备注") && remark < 0) {
                remark = c;
            }
        }
        if (carrier < 0 || price < 0 || carrier - 1 < block.start()) {
            throw layout(s, block, "表头缺「电信运营商」或「报价」列");
        }
        if (kind != RateSheet.Kind.DATA && direction < 0) {
            throw layout(s, block, "表头缺「方向」列");
        }
        int country = carrier - 1;
        int continent = carrier - 2 >= block.start() ? carrier - 2 : -1;
        List<RateSheet.Line> lines = new ArrayList<>();
        for (int r = headerRow + 1; r <= s.getLastRowNum(); r++) {
            String countryRaw = text(cell(s, r, country));
            BigDecimal unitPrice = number(cell(s, r, price));
            if (countryRaw == null || unitPrice == null) {
                continue;
            }
            lines.add(new RateSheet.Line(block.resource(), kind, continent < 0 ? null : text(cell(s, r, continent)), countryRaw,
                    text(cell(s, r, carrier)), direction < 0 ? null : text(cell(s, r, direction)), null, unitPrice,
                    remark < 0 ? null : text(cell(s, r, remark))));
        }
        return lines;
    }

    private static List<RateSheet.Line> cardFees(Sheet s) {
        for (Row row : s) {
            for (Cell cell : row) {
                if (!"卡类型".equals(text(cell))) {
                    continue;
                }
                int item = cell.getColumnIndex();
                int price = -1;
                for (Cell h : row) {
                    String name = text(h);
                    if (name != null && name.contains("报价")) {
                        price = h.getColumnIndex();
                    }
                }
                if (price < 0) {
                    throw new UserFacingException("「卡资费报价」表头缺「报价」列");
                }
                List<RateSheet.Line> lines = new ArrayList<>();
                for (int r = row.getRowNum() + 1; r <= s.getLastRowNum(); r++) {
                    String itemRaw = text(cell(s, r, item));
                    BigDecimal unitPrice = number(cell(s, r, price));
                    if (itemRaw != null && unitPrice != null) {
                        lines.add(new RateSheet.Line(RateSheet.ALL_RESOURCES, RateSheet.Kind.CARD_FEE, null, null, null, null, itemRaw, unitPrice, null));
                    }
                }
                return lines;
            }
        }
        return List.of();
    }

    private static UserFacingException layout(Sheet s, Block block, String reason) {
        return new UserFacingException("「" + s.getSheetName() + "」资源 " + block.resource() + " " + reason + "，无法识别版式");
    }

    private static Cell cell(Sheet s, int r, int c) {
        Row row = s.getRow(r);
        return row == null ? null : row.getCell(c);
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        String value = switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCachedFormulaResultType() == CellType.STRING ? cell.getStringCellValue()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            default -> null;
        };
        if (value == null) {
            return null;
        }
        String t = value.strip();
        return t.isEmpty() ? null : t;
    }

    private static BigDecimal number(Cell cell) {
        if (cell == null) {
            return null;
        }
        BigDecimal raw;
        if (cell.getCellType() == CellType.NUMERIC
                || cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC) {
            raw = BigDecimal.valueOf(cell.getNumericCellValue());
        } else {
            String t = text(cell);
            if (t == null || !t.matches("-?\\d+(\\.\\d+)?")) {
                return null;
            }
            raw = new BigDecimal(t);
        }
        return raw.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
}
