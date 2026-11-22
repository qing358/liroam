package com.liroam.fulfillment.pool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveCipher.Sealed;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.PoolItems;
import com.liroam.fulfillment.store.PoolItems.Item;

/**
 * 卡池（池模式供应商，domain-design §6.2）：运营导入预置卡号（可带交码内容），ALLOCATE 指令按卡的供应商 + 卡产品 + 形态取一张。
 * 卡号与交码内容一律加密入库，按 {@code iccid_hash} 去重（池内与已出卡都算重复）。
 */
public class CardPool {

    /** @param lpa 可空：随卡号批次提供的交码内容 */
    public record PoolItemRow(String iccid, String lpa) {

        @Override
        public String toString() {
            return "PoolItemRow[***]";
        }
    }

    public record Problem(int lineNo, String reason) {
    }

    public record ImportReport(int imported, int duplicates, List<Problem> invalid) {
    }

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final PoolItems items;
    private final SensitiveCipher cipher;
    private final AuditLog audit;

    public CardPool(TransactionOperations tx, JdbcClient jdbc, PoolItems items, SensitiveCipher cipher, AuditLog audit) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.items = items;
        this.cipher = cipher;
        this.audit = audit;
    }

    public ImportReport importItems(long supplierId, long cardProductId, List<PoolItemRow> rows, String actor) {
        String form = poolForm(supplierId, cardProductId);
        return tx.execute(status -> {
            int imported = 0;
            int duplicates = 0;
            List<Problem> invalid = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                PoolItemRow row = rows.get(i);
                String iccid;
                try {
                    iccid = SensitiveCipher.normalizeIccid(row.iccid() == null ? "" : row.iccid());
                } catch (IllegalArgumentException e) {
                    invalid.add(new Problem(i + 1, "卡号格式不对"));
                    continue;
                }
                byte[] hash = cipher.hash(SensitiveField.ICCID, iccid);
                if (items.exists(hash) || cardHasIccid(hash)) {
                    duplicates++;
                    continue;
                }
                Sealed sealedIccid = cipher.seal(SensitiveField.ICCID, iccid);
                byte[] credential = row.lpa() == null || row.lpa().isBlank() ? null : cipher.seal(SensitiveField.CREDENTIAL, row.lpa().trim()).bytes();
                items.insert(supplierId, cardProductId, form, sealedIccid, hash, SensitiveCipher.last4(iccid), credential, null);
                imported++;
            }
            audit.record(actor, "POOL_IMPORT", "CARD_PRODUCT", Long.toString(cardProductId), null,
                    "{\"imported\":" + imported + ",\"duplicates\":" + duplicates + ",\"invalid\":" + invalid.size() + "}");
            return new ImportReport(imported, duplicates, invalid);
        });
    }

    /** 事务内为卡分配一张池卡；池空返回 empty。 */
    public Optional<Item> allocate(Card card) {
        Optional<Item> item = items.lockAvailable(card.supplierId(), card.cardProductId(), card.form());
        item.ifPresent(i -> items.allocate(i.id(), card.id()));
        return item;
    }

    /** 退款归还：池卡回到可分配，卡上的卡号唯一键释放。 */
    public void returnFor(long cardAssetId) {
        items.byCard(cardAssetId).ifPresent(i -> items.returnToStock(i.id()));
    }

    public void voidFor(long cardAssetId) {
        items.byCard(cardAssetId).ifPresent(i -> items.voidItem(i.id()));
    }

    public Optional<Item> allocatedTo(long cardAssetId) {
        return items.byCard(cardAssetId);
    }

    /** 每行 {@code iccid[,lpa]}；表头行（首列不是卡号）与空行跳过。 */
    public static List<PoolItemRow> parseCsv(Reader csv) {
        List<PoolItemRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(csv)) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.replace("\uFEFF", "").split(",", 2);
                String iccid = cols[0].trim();
                if (iccid.isEmpty() || first && !iccid.matches("[0-9A-Za-z\\s-]+") || first && iccid.equalsIgnoreCase("iccid")) {
                    first = false;
                    continue;
                }
                first = false;
                rows.add(new PoolItemRow(iccid, cols.length > 1 ? cols[1].trim() : null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    private boolean cardHasIccid(byte[] hash) {
        return jdbc.sql("SELECT COUNT(*) FROM lr_card_asset WHERE iccid_hash = ?").param(hash).query(Integer.class).single() > 0;
    }

    /** 池卡形态取卡产品允许形态里的第一个（eSIM 优先）。 */
    private String poolForm(long supplierId, long cardProductId) {
        String forms = jdbc.sql("SELECT allowed_forms FROM lr_card_product WHERE id = ? AND supplier_id = ?").params(cardProductId, supplierId)
                .query(String.class).optional().orElseThrow(() -> new UserFacingException("卡产品不存在或不属于该供应商"));
        List<String> allowed = List.of(forms.split(","));
        return allowed.contains("ESIM") ? "ESIM" : allowed.getFirst().trim();
    }
}
