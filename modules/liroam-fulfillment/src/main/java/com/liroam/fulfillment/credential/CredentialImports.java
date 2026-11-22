package com.liroam.fulfillment.credential;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.fulfillment.pool.CardPool;
import com.liroam.fulfillment.pool.CardPool.PoolItemRow;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.Card;
import com.liroam.fulfillment.store.PoolItems;
import com.liroam.fulfillment.store.PoolItems.Item;
import com.liroam.supplier.spi.CredentialResult;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;

/**
 * IMPORT 通道（domain-design §7.5）：运营上传 {@code iccid,lpa} CSV，按卡号哈希匹配。
 * 命中卡 → 写交码并发事件；命中尚未分配的池卡 → 预置到池卡上，分配开通后自动交付；其余列入未匹配报告。每行一个事务，坏行不挡好行。
 */
public class CredentialImports {

    /** @param iccidLast4 只回尾四位，报告里不出现全号 */
    public record Unmatched(int lineNo, String iccidLast4, String reason) {
    }

    /**
     * @param delivered 命中卡并交付（或替换旧交码）的行数
     * @param pooled    命中尚未分配的池卡、已预置的行数
     */
    public record ImportReport(int delivered, int pooled, List<Unmatched> unmatched) {
    }

    private final TransactionOperations tx;
    private final CardAssets cards;
    private final PoolItems poolItems;
    private final CredentialDeliveries deliveries;
    private final SensitiveCipher cipher;
    private final AuditLog audit;

    public CredentialImports(TransactionOperations tx, CardAssets cards, PoolItems poolItems, CredentialDeliveries deliveries, SensitiveCipher cipher,
            AuditLog audit) {
        this.tx = tx;
        this.cards = cards;
        this.poolItems = poolItems;
        this.deliveries = deliveries;
        this.cipher = cipher;
        this.audit = audit;
    }

    public ImportReport importCsv(Reader csv, String actor) {
        List<PoolItemRow> rows = CardPool.parseCsv(csv);
        int delivered = 0;
        int pooled = 0;
        List<Unmatched> unmatched = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            int lineNo = i + 1;
            PoolItemRow row = rows.get(i);
            String iccid;
            try {
                iccid = SensitiveCipher.normalizeIccid(row.iccid());
            } catch (IllegalArgumentException e) {
                unmatched.add(new Unmatched(lineNo, null, "卡号格式不对"));
                continue;
            }
            if (row.lpa() == null || row.lpa().isBlank()) {
                unmatched.add(new Unmatched(lineNo, SensitiveCipher.last4(iccid), "缺激活码"));
                continue;
            }
            String outcome = tx.execute(status -> importRow(iccid, row.lpa().trim(), actor));
            switch (outcome) {
                case "DELIVERED" -> delivered++;
                case "POOLED" -> pooled++;
                default -> unmatched.add(new Unmatched(lineNo, SensitiveCipher.last4(iccid), outcome));
            }
        }
        audit.record(actor, "CREDENTIAL_IMPORT", "CARD_CREDENTIAL", "-", null,
                "{\"delivered\":" + delivered + ",\"pooled\":" + pooled + ",\"unmatched\":" + unmatched.size() + "}");
        return new ImportReport(delivered, pooled, unmatched);
    }

    private String importRow(String iccid, String lpa, String actor) {
        byte[] hash = cipher.hash(SensitiveField.ICCID, iccid);
        Optional<Card> card = cards.findByIccidHash(hash).flatMap(c -> cards.lock(c.id()));
        if (card.isPresent()) {
            if (!CredentialEntry.accepts(card.get())) {
                return "卡当前状态 " + card.get().status() + " 不能交码";
            }
            deliveries.deliver(card.get().id(), CredentialResult.Kind.LPA, lpa, CredentialChannel.IMPORT, actor);
            return "DELIVERED";
        }
        Optional<Item> item = poolItems.byIccidHash(hash);
        if (item.isPresent() && "IN_STOCK".equals(item.get().status())) {
            poolItems.preloadCredential(item.get().id(), cipher.seal(SensitiveField.ICCID, iccid), cipher.seal(SensitiveField.CREDENTIAL, lpa));
            return "POOLED";
        }
        return "找不到这张卡";
    }
}
