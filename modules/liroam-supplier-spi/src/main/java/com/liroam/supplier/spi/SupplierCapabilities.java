package com.liroam.supplier.spi;

import java.util.Objects;
import java.util.Set;

/**
 * 能力声明（domain-design §6.2）。上层只按这里分支，不按供应商分支。
 *
 * @param rateLimitPerMinute 全局令牌桶容量，必须为正；可被 {@code lr_supplier.rate_limit_per_min} 覆盖
 */
public record SupplierCapabilities(
        InventoryMode inventoryMode,
        Set<CredentialChannel> credentialChannels,
        Set<UsageDimension> usageDimensions,
        Set<StatusOp> statusOps,
        TopUpMode topUpMode,
        boolean kycQuery,
        boolean webhook,
        boolean idempotencyKey,
        int rateLimitPerMinute) {

    public SupplierCapabilities {
        Objects.requireNonNull(inventoryMode, "inventoryMode");
        Objects.requireNonNull(topUpMode, "topUpMode");
        credentialChannels = Set.copyOf(credentialChannels);
        usageDimensions = Set.copyOf(usageDimensions);
        statusOps = Set.copyOf(statusOps);
        if (credentialChannels.isEmpty()) {
            throw new IllegalArgumentException("至少声明一种交码通道");
        }
        if (rateLimitPerMinute <= 0) {
            throw new IllegalArgumentException("rateLimitPerMinute 必须为正");
        }
    }

    public enum InventoryMode {
        /** 需预置卡号（Idle ICCID）：先从卡池分配再开通。 */
        POOL_REQUIRED,
        /** 下单即由上游创建并返回卡号。 */
        CREATE_ON_ORDER
    }

    public enum CredentialChannel { API, IMPORT, MANUAL }

    public enum UsageDimension { DATA, VOICE, SMS, DAILY_DATA }

    public enum StatusOp { SUSPEND, RESUME, TERMINATE }

    public enum TopUpMode { ADD_PACKAGE, TOP_UP_QUOTA, NEW_SUBSCRIPTION }
}
