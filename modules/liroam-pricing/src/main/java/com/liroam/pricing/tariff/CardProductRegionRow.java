package com.liroam.pricing.tariff;

import java.math.BigDecimal;

/**
 * 卡产品的一个可用区域（ACTIVE 资费表口径）。
 *
 * @param unitCostHkd 流量港币单价 / GB（已按运营商规则折算）
 */
public record CardProductRegionRow(long regionId, String code, String name, String continent, boolean kycRequired, String note,
        BigDecimal unitCostHkd) {
}
