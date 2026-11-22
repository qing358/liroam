package com.liroam.supplier.spi.contract.stub;

import java.util.Set;

import com.liroam.supplier.spi.SupplierCapabilities;
import com.liroam.supplier.spi.SupplierCapabilities.CredentialChannel;
import com.liroam.supplier.spi.SupplierCapabilities.InventoryMode;
import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;
import com.liroam.supplier.spi.SupplierCapabilities.TopUpMode;
import com.liroam.supplier.spi.SupplierCapabilities.UsageDimension;

/** 两种对立的能力形态，确保套件的每个按能力分支都被走到。 */
public enum StubShape {
    /** 池模式、导入/人工交码、无幂等键（走查档）、无回调、三种状态操作；上游用 MB 与东八区本地时间。 */
    POOL_IMPORT(new SupplierCapabilities(
            InventoryMode.POOL_REQUIRED,
            Set.of(CredentialChannel.IMPORT, CredentialChannel.MANUAL),
            Set.of(UsageDimension.DATA, UsageDimension.DAILY_DATA),
            Set.of(StatusOp.SUSPEND, StatusOp.RESUME, StatusOp.TERMINATE),
            TopUpMode.ADD_PACKAGE,
            false, false, false, 100)),
    /** 下单即建、API 交码、幂等键、回调、只支持终止；上游用字节与 ISO-8601。 */
    CREATE_API(new SupplierCapabilities(
            InventoryMode.CREATE_ON_ORDER,
            Set.of(CredentialChannel.API),
            Set.of(UsageDimension.DATA, UsageDimension.VOICE, UsageDimension.SMS),
            Set.of(StatusOp.TERMINATE),
            TopUpMode.NEW_SUBSCRIPTION,
            false, true, true, 600));

    private final SupplierCapabilities capabilities;

    StubShape(SupplierCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public SupplierCapabilities capabilities() {
        return capabilities;
    }
}
