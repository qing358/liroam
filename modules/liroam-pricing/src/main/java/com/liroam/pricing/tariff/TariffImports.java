package com.liroam.pricing.tariff;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.SupplierDirectory;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.DisplayTime;
import com.liroam.pricing.Json;
import com.liroam.pricing.PricingSettings;
import com.liroam.pricing.price.RepriceTrigger;
import com.liroam.pricing.price.Repricing;
import com.liroam.pricing.region.RegionDictionary;
import com.liroam.pricing.region.RegionDictionary.KnownRegion;

/**
 * 资费导入与激活（domain-design §8.1）。
 * <p>
 * 导入：原样行进 {@code lr_tariff_line}、按运营商规则折出本表的 {@code lr_region_tariff}；卡产品与（未启用的）可用区域行按需建档。
 * 草稿不改前台：可用区域的启用、实名标记、限制说明与卡产品限制只在激活时按本表重新导出，激活后对 LISTED SKU 重算成本。
 */
public class TariffImports {

    /** 资费表日期是供应商所在时区的自然日。 */
    private static final ZoneId SHEET_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final int INSERT_CHUNK = 200;

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final Settings settings;
    private final AuditLog audit;
    private final SupplierDirectory suppliers;
    private final RegionDictionary dictionary;
    private final Repricing repricing;

    public TariffImports(JdbcClient jdbc, TransactionOperations tx, Settings settings, AuditLog audit, SupplierDirectory suppliers,
            RegionDictionary dictionary, Repricing repricing) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.audit = audit;
        this.suppliers = suppliers;
        this.dictionary = dictionary;
        this.repricing = repricing;
    }

    /** 同一供应商重复上传同一文件返回原草稿/版本，不重复入库。 */
    public long importXlsx(long supplierId, String fileName, InputStream content, String actor) {
        if (suppliers.find(supplierId).isEmpty()) {
            throw new UserFacingException("供应商 #" + supplierId + " 不存在");
        }
        byte[] bytes;
        try {
            bytes = content.readAllBytes();
        } catch (IOException e) {
            throw new UserFacingException("读取上传文件失败，请重试");
        }
        String sha256 = sha256(bytes);
        Optional<Long> existing = jdbc.sql("SELECT id FROM lr_tariff_sheet WHERE supplier_id = ? AND file_sha256 = ?")
                .params(supplierId, sha256).query(Long.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        RateSheet sheet = RateSheetParser.parse(bytes);
        CarrierRule rule = settings.get(PricingSettings.TARIFF_CARRIER_RULE, SettingScope.supplier(supplierId));
        return tx.execute(status -> store(supplierId, fileName, sha256, sheet, rule, actor));
    }

    private long store(long supplierId, String fileName, String sha256, RateSheet sheet, CarrierRule rule, String actor) {
        Map<String, Long> regionIds = new HashMap<>();
        List<Long> lineRegions = new ArrayList<>();
        for (RateSheet.Line line : sheet.lines()) {
            Optional<KnownRegion> region = line.countryRaw() == null ? Optional.empty() : dictionary.match(line.countryRaw());
            lineRegions.add(region.map(r -> regionIds.computeIfAbsent(r.code(), c -> ensureRegion(r, line.continentRaw()))).orElse(null));
        }
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_tariff_sheet (supplier_id, currency, effective_from, file_name, file_sha256, status, imported_by, notes_raw)
                VALUES (?, 'HKD', ?, ?, ?, 'DRAFT', ?, ?)
                """).params(supplierId, UtcColumns.toColumn(sheet.effectiveFrom().atStartOfDay(SHEET_ZONE).toInstant()), fileName, sha256,
                actor, sheet.notesRaw()).update(key);
        long sheetId = key.getKey().longValue();
        insertLines(sheetId, sheet.lines(), lineRegions);

        Map<String, Long> products = ensureCardProducts(supplierId, sheet);
        Map<Long, String> codeByRegion = new HashMap<>();
        regionIds.forEach((code, id) -> codeByRegion.put(id, code));
        insertRegionTariffs(sheetId, products, codeByRegion, rule);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("supplierId", supplierId);
        after.put("fileName", fileName);
        after.put("sha256", sha256);
        after.put("lines", sheet.lines().size());
        audit.record(actor, "TARIFF_IMPORT", "TARIFF_SHEET", String.valueOf(sheetId), null, Json.write(after));
        return sheetId;
    }

    private long ensureRegion(KnownRegion region, String continent) {
        jdbc.sql("""
                INSERT INTO lr_usage_region (code, name_zh, continent) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE continent = COALESCE(continent, VALUES(continent))
                """).params(region.code(), region.name(), continent).update();
        return jdbc.sql("SELECT id FROM lr_usage_region WHERE code = ?").param(region.code()).query(Long.class).single();
    }

    private void insertLines(long sheetId, List<RateSheet.Line> lines, List<Long> regions) {
        for (int from = 0; from < lines.size(); from += INSERT_CHUNK) {
            int to = Math.min(from + INSERT_CHUNK, lines.size());
            StringJoiner values = new StringJoiner(", ");
            List<Object> params = new ArrayList<>();
            for (int i = from; i < to; i++) {
                RateSheet.Line l = lines.get(i);
                values.add("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                params.addAll(Arrays.asList(sheetId, l.resourceCode(), l.kind().name(), l.continentRaw(), l.countryRaw(), regions.get(i),
                        l.carrierRaw(), l.directionRaw(), l.itemRaw(), l.kind().unit, l.unitPrice(), l.remarkRaw()));
            }
            jdbc.sql("INSERT INTO lr_tariff_line (sheet_id, resource_code, kind, continent_raw, country_raw, region_id, carrier_raw, direction_raw, "
                    + "item_raw, unit, unit_price, remark_raw) VALUES " + values).params(params).update();
        }
    }

    /** 新资源建卡产品档；已有的卡产品等激活时再按本表更新。 */
    private Map<String, Long> ensureCardProducts(long supplierId, RateSheet sheet) {
        String forms = CardForms.fromCardFees(sheet.lines().stream()
                .filter(l -> l.kind() == RateSheet.Kind.CARD_FEE).map(RateSheet.Line::itemRaw).toList(), false);
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String resource : resources(sheet)) {
            boolean voice = sheet.lines().stream().anyMatch(l -> l.resourceCode().equals(resource) && l.kind() == RateSheet.Kind.VOICE);
            boolean sms = sheet.lines().stream().anyMatch(l -> l.resourceCode().equals(resource) && l.kind() == RateSheet.Kind.SMS);
            jdbc.sql("""
                    INSERT INTO lr_card_product (supplier_id, home_location, card_type_code, name, cap_data, cap_voice, cap_sms, allowed_forms)
                    VALUES (?, ?, ?, ?, 1, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE id = id
                    """).params(supplierId, HomeLocation.ofResource(resource), resource, resource, voice, sms, forms).update();
            ids.put(resource, jdbc.sql("SELECT id FROM lr_card_product WHERE supplier_id = ? AND card_type_code = ?")
                    .params(supplierId, resource).query(Long.class).single());
        }
        return ids;
    }

    private static Set<String> resources(RateSheet sheet) {
        Set<String> set = new LinkedHashSet<>();
        sheet.lines().stream().filter(l -> l.kind() != RateSheet.Kind.CARD_FEE).forEach(l -> set.add(l.resourceCode()));
        return set;
    }

    private record StoredLine(long id, String resourceCode, String kind, long regionId, String directionRaw, BigDecimal unitPrice) {
    }

    private record TariffKey(String resourceCode, long regionId, String kind, String direction) {
    }

    private void insertRegionTariffs(long sheetId, Map<String, Long> products, Map<Long, String> codeByRegion, CarrierRule rule) {
        List<StoredLine> lines = jdbc.sql("""
                SELECT id, resource_code, kind, region_id, direction_raw, unit_price FROM lr_tariff_line
                 WHERE sheet_id = ? AND kind <> 'CARD_FEE' AND region_id IS NOT NULL ORDER BY id
                """).param(sheetId).query(StoredLine.class).list();
        Map<TariffKey, List<StoredLine>> groups = new LinkedHashMap<>();
        for (StoredLine l : lines) {
            String direction = Directions.normalize(l.kind(), l.directionRaw(), codeByRegion.get(l.regionId()), dictionary);
            groups.computeIfAbsent(new TariffKey(l.resourceCode(), l.regionId(), l.kind(), direction), k -> new ArrayList<>()).add(l);
        }
        Map<List<Long>, Long> cprIds = new HashMap<>();
        for (Map.Entry<TariffKey, List<StoredLine>> g : groups.entrySet()) {
            TariffKey k = g.getKey();
            long cpr = cprIds.computeIfAbsent(List.of(products.get(k.resourceCode()), k.regionId()), pr -> cardProductRegion(pr.get(0), pr.get(1)));
            List<StoredLine> rows = g.getValue();
            jdbc.sql("""
                    INSERT INTO lr_region_tariff (sheet_id, card_product_region_id, kind, direction, unit, unit_cost, carrier_rule, source_line_ids_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                    """).params(sheetId, cpr, k.kind(), k.direction(), RateSheet.Kind.valueOf(k.kind()).unit,
                    rule.fold(rows.stream().map(StoredLine::unitPrice).toList()), rule.name(),
                    Json.write(rows.stream().map(StoredLine::id).toList())).update();
        }
    }

    /** 新区域建未启用的行，已有行（可能正被前台使用）不动。 */
    private long cardProductRegion(long productId, long regionId) {
        jdbc.sql("INSERT INTO lr_card_product_region (card_product_id, region_id, active) VALUES (?, ?, 0) ON DUPLICATE KEY UPDATE id = id")
                .params(productId, regionId).update();
        return jdbc.sql("SELECT id FROM lr_card_product_region WHERE card_product_id = ? AND region_id = ?")
                .params(productId, regionId).query(Long.class).single();
    }

    private record SheetHead(long supplierId, String status, String notesRaw) {
    }

    private record DataRemark(long regionId, String code, String remarkRaw) {
    }

    /** 只有草稿可激活；同供应商原 ACTIVE 版本置 SUPERSEDED。 */
    public ImportReport activate(long sheetId, String actor) {
        tx.executeWithoutResult(status -> {
            SheetHead head = jdbc.sql("SELECT supplier_id, status, notes_raw FROM lr_tariff_sheet WHERE id = ? FOR UPDATE")
                    .param(sheetId).query(SheetHead.class).optional()
                    .orElseThrow(() -> new UserFacingException("资费表 #" + sheetId + " 不存在"));
            if (!"DRAFT".equals(head.status())) {
                throw new UserFacingException("资费表 #" + sheetId + " 当前为 " + head.status() + "，只有草稿可以激活");
            }
            jdbc.sql("UPDATE lr_tariff_sheet SET status = 'SUPERSEDED', version = version + 1, updated_at = UTC_TIMESTAMP(3) "
                    + "WHERE supplier_id = ? AND status = 'ACTIVE'").param(head.supplierId()).update();
            jdbc.sql("UPDATE lr_tariff_sheet SET status = 'ACTIVE', version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?")
                    .param(sheetId).update();
            jdbc.sql("""
                    UPDATE lr_card_product_region cpr JOIN lr_card_product p ON p.id = cpr.card_product_id
                       SET cpr.active = 0, cpr.updated_at = UTC_TIMESTAMP(3)
                     WHERE p.supplier_id = ? AND cpr.active = 1
                    """).param(head.supplierId()).update();
            applySheet(sheetId, head);
            audit.record(actor, "TARIFF_ACTIVATE", "TARIFF_SHEET", String.valueOf(sheetId), null,
                    Json.write(Map.of("supplierId", head.supplierId())));
        });
        repricing.recomputeAll(RepriceTrigger.TARIFF, actor);
        return report(sheetId);
    }

    private void applySheet(long sheetId, SheetHead head) {
        List<String> resources = jdbc.sql("SELECT resource_code FROM lr_tariff_line WHERE sheet_id = ? AND kind <> 'CARD_FEE' "
                + "GROUP BY resource_code ORDER BY MIN(id)").param(sheetId).query(String.class).list();
        List<String> cardItems = jdbc.sql("SELECT item_raw FROM lr_tariff_line WHERE sheet_id = ? AND kind = 'CARD_FEE'")
                .param(sheetId).query(String.class).list();
        List<RegionNotes.Footnote> footnotes = RegionNotes.footnotes(head.notesRaw());
        for (String resource : resources) {
            long productId = jdbc.sql("SELECT id FROM lr_card_product WHERE supplier_id = ? AND card_type_code = ?")
                    .params(head.supplierId(), resource).query(Long.class).single();
            boolean softOpened = CardForms.allows(jdbc.sql("SELECT allowed_forms FROM lr_card_product WHERE id = ?").param(productId)
                    .query(String.class).single(), CardForms.SOFT);
            List<DataRemark> rows = jdbc.sql("""
                    SELECT l.region_id, r.code, l.remark_raw FROM lr_tariff_line l JOIN lr_usage_region r ON r.id = l.region_id
                     WHERE l.sheet_id = ? AND l.resource_code = ? AND l.kind = 'DATA' ORDER BY l.id
                    """).params(sheetId, resource).query(DataRemark.class).list();
            Map<String, Long> regionIds = new LinkedHashMap<>();
            Map<String, List<String>> remarks = new LinkedHashMap<>();
            for (DataRemark row : rows) {
                regionIds.put(row.code(), row.regionId());
                List<String> list = remarks.computeIfAbsent(row.code(), c -> new ArrayList<>());
                if (row.remarkRaw() != null && !row.remarkRaw().isBlank() && !"-".equals(row.remarkRaw().strip()) && !list.contains(row.remarkRaw())) {
                    list.add(row.remarkRaw());
                }
            }
            RegionNotes.Result derived = RegionNotes.derive(resource, resources, footnotes, remarks, dictionary);
            boolean anyKyc = derived.byRegion().values().stream().anyMatch(RegionNotes.RegionNote::kycRequired);
            boolean voice = hasKind(sheetId, resource, "VOICE");
            boolean sms = hasKind(sheetId, resource, "SMS");
            jdbc.sql("""
                    UPDATE lr_card_product SET kyc_policy = ?, cap_voice = ?, cap_sms = ?, allowed_forms = ?, constraints_json = CAST(? AS JSON),
                           updated_at = UTC_TIMESTAMP(3)
                     WHERE id = ?
                    """).params(anyKyc ? "CONDITIONAL" : "NONE", voice, sms, CardForms.fromCardFees(cardItems, softOpened),
                    derived.productNotes().isEmpty() ? null : Json.write(Map.of("notes", derived.productNotes())), productId).update();
            derived.byRegion().forEach((code, note) -> jdbc.sql("""
                    INSERT INTO lr_card_product_region (card_product_id, region_id, active, kyc_required, restriction_note) VALUES (?, ?, 1, ?, ?)
                    ON DUPLICATE KEY UPDATE active = 1, kyc_required = VALUES(kyc_required), restriction_note = VALUES(restriction_note),
                                            updated_at = UTC_TIMESTAMP(3)
                    """).params(productId, regionIds.get(code), note.kycRequired(), note.note()).update());
        }
    }

    private boolean hasKind(long sheetId, String resource, String kind) {
        return jdbc.sql("SELECT COUNT(*) FROM lr_tariff_line WHERE sheet_id = ? AND resource_code = ? AND kind = ?")
                .params(sheetId, resource, kind).query(Integer.class).single() > 0;
    }

    private record SheetRow(long id, long supplierId, String fileName, String status, LocalDateTime effectiveFrom, String importedBy,
            LocalDateTime createdAt) {

        TariffSheetRow view() {
            return new TariffSheetRow(id, supplierId, fileName, status, DisplayTime.format(UtcColumns.fromColumn(effectiveFrom)), importedBy,
                    DisplayTime.format(UtcColumns.fromColumn(createdAt)));
        }
    }

    private static final String SHEET_SELECT = "SELECT id, supplier_id, file_name, status, effective_from, imported_by, created_at FROM lr_tariff_sheet";

    public ImportReport report(long sheetId) {
        TariffSheetRow sheet = jdbc.sql(SHEET_SELECT + " WHERE id = ?").param(sheetId).query(SheetRow.class).optional()
                .orElseThrow(() -> new UserFacingException("资费表 #" + sheetId + " 不存在")).view();
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (RateSheet.Kind kind : RateSheet.Kind.values()) {
            int n = jdbc.sql("SELECT COUNT(*) FROM lr_tariff_line WHERE sheet_id = ? AND kind = ?").params(sheetId, kind.name())
                    .query(Integer.class).single();
            if (n > 0) {
                byKind.put(kind.name(), n);
            }
        }
        List<String> unmatched = jdbc.sql("""
                SELECT country_raw FROM lr_tariff_line WHERE sheet_id = ? AND kind <> 'CARD_FEE' AND region_id IS NULL
                 GROUP BY country_raw ORDER BY MIN(id)
                """).param(sheetId).query(String.class).list();
        List<ImportReport.ResourceSummary> resources = jdbc.sql("""
                SELECT resource_code, COUNT(DISTINCT region_id) FROM lr_tariff_line WHERE sheet_id = ? AND kind = 'DATA'
                 GROUP BY resource_code ORDER BY MIN(id)
                """).param(sheetId).query((rs, i) -> new ImportReport.ResourceSummary(rs.getString(1), rs.getInt(2))).list();
        return new ImportReport(sheet.id(), sheet.supplierId(), sheet.fileName(), sheet.status(), sheet.effectiveFromText(),
                byKind.values().stream().mapToInt(Integer::intValue).sum(), byKind, unmatched, resources);
    }

    /** @param supplierId 为空列全部供应商 */
    public PageResult<TariffSheetRow> page(Long supplierId, int pageNumber, int pageSize) {
        String where = supplierId == null ? "" : " WHERE supplier_id = " + supplierId.longValue();
        long total = jdbc.sql("SELECT COUNT(*) FROM lr_tariff_sheet" + where).query(Long.class).single();
        List<TariffSheetRow> rows = jdbc.sql(SHEET_SELECT + where + " ORDER BY id DESC LIMIT ? OFFSET ?")
                .params(PageResult.clampSize(pageSize), PageResult.offset(pageNumber, pageSize)).query(SheetRow.class).list()
                .stream().map(SheetRow::view).toList();
        return new PageResult<>(rows, total, Math.max(pageNumber, 1), PageResult.clampSize(pageSize));
    }

    /** 卡产品当前（ACTIVE 资费表）可用区域；只有草稿时为空。 */
    public List<CardProductRegionRow> cardProductRegions(long cardProductId) {
        return jdbc.sql("""
                SELECT r.id AS region_id, r.code, r.name_zh AS name, r.continent, cpr.kyc_required, cpr.restriction_note AS note,
                       rt.unit_cost AS unit_cost_hkd
                  FROM lr_card_product_region cpr
                  JOIN lr_card_product p ON p.id = cpr.card_product_id
                  JOIN lr_tariff_sheet s ON s.supplier_id = p.supplier_id AND s.status = 'ACTIVE'
                  JOIN lr_region_tariff rt ON rt.sheet_id = s.id AND rt.card_product_region_id = cpr.id AND rt.kind = 'DATA'
                  JOIN lr_usage_region r ON r.id = cpr.region_id
                 WHERE cpr.card_product_id = ? AND cpr.active = 1
                 ORDER BY r.code
                """).param(cardProductId).query(CardProductRegionRow.class).list();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
