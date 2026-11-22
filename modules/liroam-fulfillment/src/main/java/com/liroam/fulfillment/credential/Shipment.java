package com.liroam.fulfillment.credential;

/**
 * 实体卡的交码：寄出的物流。
 *
 * @param logisticsRef 壳物流公司 id（{@code li_logistics.id}），整单回写壳发货用
 */
public record Shipment(String logisticsRef, String logisticsCompany, String logisticsNo) {

    boolean complete() {
        return !blank(logisticsRef) && !blank(logisticsCompany) && !blank(logisticsNo);
    }

    Shipment trimmed() {
        return new Shipment(logisticsRef.trim(), logisticsCompany.trim(), logisticsNo.trim());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
