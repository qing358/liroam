package com.liroam.pricing.fx;

import java.math.BigDecimal;

/**
 * 一次同步的结果。失败时 {@code versionId}/{@code rate} 为空，{@code error} 是给运营看的原因。
 */
public record FxSyncResult(boolean ok, Long versionId, BigDecimal rate, String sourceKey, String error) {

    static FxSyncResult success(long versionId, BigDecimal rate, String sourceKey) {
        return new FxSyncResult(true, versionId, rate, sourceKey, null);
    }

    static FxSyncResult failure(String sourceKey, String error) {
        return new FxSyncResult(false, null, null, sourceKey, error);
    }
}
