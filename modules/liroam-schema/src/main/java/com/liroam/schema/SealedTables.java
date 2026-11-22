package com.liroam.schema;

import java.util.List;

import com.liroam.common.crypto.SensitiveField;

/**
 * 自有表里所有密文列的唯一登记（{@code *_cipher}，与同行 {@code key_version} 一起由 {@code SensitiveCipher} 加解密）。
 * 轮换数据密钥时按此表重加密；新加密文列必须在这里登记（{@code SealedTablesTest} 对照真实表结构）。
 */
public final class SealedTables {

    public static final String KEY_VERSION_COLUMN = "key_version";

    /** @param field 加密时用的字段种类（GCM 附加数据），解密须一致 */
    public record SealedColumn(String column, SensitiveField field) {
    }

    public record SealedTable(String table, List<SealedColumn> columns) {

        public SealedTable {
            columns = List.copyOf(columns);
        }
    }

    public static final List<SealedTable> ALL = List.of(
            new SealedTable("lr_card_asset", List.of(new SealedColumn("iccid_cipher", SensitiveField.ICCID))),
            new SealedTable("lr_card_pool_item", List.of(new SealedColumn("iccid_cipher", SensitiveField.ICCID),
                    new SealedColumn("credential_cipher", SensitiveField.CREDENTIAL))),
            new SealedTable("lr_card_credential", List.of(new SealedColumn("payload_cipher", SensitiveField.CREDENTIAL))),
            new SealedTable("lr_channel_order", List.of(new SealedColumn("buyer_contact_cipher", SensitiveField.BUYER_CONTACT))),
            new SealedTable("lr_withdrawal", List.of(new SealedColumn("payee_cipher", SensitiveField.PAYEE))));

    private SealedTables() {
    }
}
