package com.liroam.ops.recon;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;
import com.liroam.ops.recon.ReconRuns.DiffKind;
import com.liroam.ops.recon.ReconRuns.NewDiff;
import com.liroam.ops.recon.ReconRuns.Run;
import com.liroam.ops.recon.StatementCsv.Row;

/**
 * 上游供应商日对账：我方 = 该供应商当天（结算时区自然日）成功的开卡 / 加包指令，按卡汇总，金额 = 订单行快照成本版本的港币成本；
 * 对方 = 运营导入的供应商账单（CSV：iccid, item, amount_hkd 元）按 ICCID 汇总。按 ICCID 哈希比对，账单行只存哈希与尾号，不落明文。
 * 供应商真实账单格式【待核实】，先用本模板；格式转换放在导入前。
 */
public class SupplierReconciliation {

    public static final String KIND = "SUPPLIER_DAILY";

    private record Ours(long cardId, byte[] iccidHash, long amountMinor) {
    }

    private record Theirs(String last4, long amountMinor) {
    }

    private static final String OURS = """
            SELECT c.id AS card_id, c.iccid_hash, COALESCE(SUM(cv.cost_hkd_minor), 0) AS amount_minor
              FROM lr_supplier_command cmd
              JOIN lr_card_asset c ON c.id = cmd.card_asset_id
              LEFT JOIN lr_card_plan p ON p.id = cmd.card_plan_id
              LEFT JOIN lr_fulfillment_line l ON l.id = COALESCE(p.fulfillment_line_id, c.fulfillment_line_id)
              LEFT JOIN lr_order_line_snapshot s ON s.source_type = l.source_type AND s.source_line_ref = l.source_line_ref
              LEFT JOIN lr_sku_cost_version cv ON cv.id = s.cost_version_id
             WHERE cmd.supplier_id = ? AND cmd.status = 'SUCCEEDED' AND cmd.type IN ('PROVISION', 'ADD_PLAN')
               AND cmd.updated_at >= ? AND cmd.updated_at < ? AND c.iccid_hash IS NOT NULL
             GROUP BY c.id, c.iccid_hash
             ORDER BY c.id
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final SensitiveCipher cipher;
    private final ReconRuns runs;
    private final ZoneId zone;

    public SupplierReconciliation(TransactionOperations tx, JdbcClient jdbc, SensitiveCipher cipher, ReconRuns runs, ZoneId zone) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.runs = runs;
        this.zone = zone;
    }

    public Run run(long supplierId, LocalDate day, String csv, String actor) {
        if (jdbc.sql("SELECT COUNT(*) FROM lr_supplier WHERE id = ?").param(supplierId).query(Long.class).single() == 0) {
            throw new UserFacingException("供应商不存在");
        }
        List<Row> rows = StatementCsv.parse(csv, "iccid", "item", "amount_hkd");
        Map<String, Theirs> theirs = new LinkedHashMap<>();
        List<Object[]> lines = new ArrayList<>();
        for (Row r : rows) {
            String iccid;
            try {
                iccid = SensitiveCipher.normalizeIccid(r.get("iccid"));
            } catch (IllegalArgumentException e) {
                throw StatementCsv.bad(r, " ICCID 不对");
            }
            if (iccid.length() < 18) {
                throw StatementCsv.bad(r, " ICCID 不对（至少 18 位）");
            }
            long amount = StatementCsv.minor(r, "amount_hkd");
            byte[] hash = cipher.hash(SensitiveField.ICCID, iccid);
            String last4 = SensitiveCipher.last4(iccid);
            theirs.merge(HexFormat.of().formatHex(hash), new Theirs(last4, amount), (a, b) -> new Theirs(a.last4(), a.amountMinor() + b.amountMinor()));
            String item = r.get("item").isEmpty() ? "-" : r.get("item");
            lines.add(new Object[] {hash, item.length() > 128 ? item.substring(0, 128) : item, amount, last4});
        }
        LocalDateTime from = UtcColumns.toColumn(day.atStartOfDay(zone).toInstant());
        LocalDateTime to = UtcColumns.toColumn(day.plusDays(1).atStartOfDay(zone).toInstant());
        List<Ours> ours = jdbc.sql(OURS).params(supplierId, from, to).query(Ours.class).list();

        List<NewDiff> diffs = new ArrayList<>();
        int matched = 0;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Ours o : ours) {
            String key = HexFormat.of().formatHex(o.iccidHash());
            seen.put(key, true);
            Theirs t = theirs.get(key);
            if (t == null) {
                diffs.add(new NewDiff(DiffKind.MISSING_THEIRS, "card:" + o.cardId(), null, o.amountMinor(), null));
            } else if (t.amountMinor() != o.amountMinor()) {
                diffs.add(new NewDiff(DiffKind.AMOUNT, "card:" + o.cardId(), "ICCID ****" + t.last4(), o.amountMinor(), t.amountMinor()));
            } else {
                matched++;
            }
        }
        theirs.forEach((key, t) -> {
            if (!seen.containsKey(key)) {
                diffs.add(new NewDiff(DiffKind.MISSING_OURS, null, "ICCID ****" + t.last4(), null, t.amountMinor()));
            }
        });
        int matchedCount = matched;
        long runId = tx.execute(s -> {
            long id = runs.record(KIND, day.toString(), Long.toString(supplierId), matchedCount, diffs, "HKD", actor);
            for (Object[] l : lines) {
                jdbc.sql("""
                        INSERT INTO lr_supplier_statement_line (run_id, supplier_id, iccid_hash, item, amount_hkd_minor, raw_json)
                        VALUES (?, ?, ?, ?, ?, JSON_OBJECT('iccidLast4', ?))
                        """).params(id, supplierId, l[0], l[1], l[2], l[3]).update();
            }
            return id;
        });
        return runs.run(runId);
    }
}
