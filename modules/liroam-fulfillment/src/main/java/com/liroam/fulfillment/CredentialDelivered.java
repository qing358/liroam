package com.liroam.fulfillment;

import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;

/**
 * 交码领域事件（domain-design §7.5）：API / IMPORT / MANUAL 三通道写完 {@code lr_card_credential} 后同一事务内发布。
 * 卡状态迁移、履约行推进、壳发货回写只订阅它；佣金计时（P5）、通知亦从这里接。
 */
public record CredentialDelivered(long cardAssetId, CredentialChannel channel) {
}
