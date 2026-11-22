package com.liroam.supplier.spi;

import java.util.Map;

/**
 * 开卡/订购。
 *
 * @param requestNo     本站幂等号；声明 idempotencyKey 的供应商透传给上游
 * @param iccid         池模式必填（卡池分配所得），下单即建模式为空
 * @param offeringCode  该供应商的套餐码（{@code lr_supplier_offering.upstream_package_code}）
 * @param offeringPayload 套餐映射里的额外参数
 */
public record ProvisionCommand(String requestNo, long cardAssetId, String iccid, String offeringCode, Map<String, String> offeringPayload) {

    public ProvisionCommand {
        offeringPayload = offeringPayload == null ? Map.of() : Map.copyOf(offeringPayload);
    }
}
