package com.liroam.fulfillment.courier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 实体卡物流轨迹来源（可换家：壳物流插件 / 合作快递直连 / mock）。按 {@code logistics.tracking_source} 配置的 {@link #key()} 选用。
 */
public interface CourierTracking {

    String key();

    /** @return empty = 来源查不到（未配置、快递无数据或接口失败） */
    Optional<Track> track(Waybill waybill);

    /**
     * @param orderSn      壳订单号（来源需要收件人手机号校验时据此取）
     * @param logisticsRef 壳物流公司 id（{@code li_logistics.id}）
     */
    record Waybill(String orderSn, String logisticsRef, String logisticsCompany, String logisticsNo, Instant shippedAt) {
    }

    /** @param events 新的在前 */
    record Track(List<TrackEvent> events) {
    }

    record TrackEvent(String timeText, String description) {
    }
}
