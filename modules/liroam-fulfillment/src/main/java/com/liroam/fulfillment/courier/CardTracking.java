package com.liroam.fulfillment.courier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.setting.Settings;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.fulfillment.FulfillmentSettings;
import com.liroam.fulfillment.courier.CourierTracking.Track;
import com.liroam.fulfillment.courier.CourierTracking.TrackEvent;
import com.liroam.fulfillment.courier.CourierTracking.Waybill;
import com.liroam.fulfillment.store.CardCredentials;

/** 「查看物流」：实体卡当前运单 + 配置来源的轨迹；来源缺失或查不到时只给运单号与提示。 */
public class CardTracking {

    /** @param message 无轨迹时给买家的提示；有轨迹为 null */
    public record TrackingView(String logisticsCompany, String logisticsNo, String shippedText, String message, List<TrackEvent> events) {
    }

    private final JdbcClient jdbc;
    private final CardCredentials credentials;
    private final Settings settings;
    private final Supplier<List<CourierTracking>> sources;

    public CardTracking(JdbcClient jdbc, CardCredentials credentials, Settings settings, Supplier<List<CourierTracking>> sources) {
        this.jdbc = jdbc;
        this.credentials = credentials;
        this.settings = settings;
        this.sources = sources;
    }

    static final String UNAVAILABLE = "物流查询暂不可用，请凭运单号到快递公司官网查询";
    static final String NOT_YET = "暂未查到物流轨迹，快递揽收后通常数小时内更新";

    private record CardOrder(long id, String ownerMemberId, String orderSn) {
    }

    /** 本进程已装配的轨迹来源（{@code logistics.tracking_source} 可选值）。 */
    public List<String> sourceKeys() {
        return sources.get().stream().map(CourierTracking::key).toList();
    }

    public Optional<TrackingView> forMember(long cardAssetId, String memberId) {
        return view(card(cardAssetId).filter(c -> memberId.equals(c.ownerMemberId())).orElseThrow(() -> new UserFacingException("找不到这张卡")));
    }

    public Optional<TrackingView> forOperator(long cardAssetId) {
        return view(card(cardAssetId).orElseThrow(() -> new UserFacingException("找不到这张卡")));
    }

    private Optional<CardOrder> card(long cardAssetId) {
        return jdbc.sql("""
                SELECT c.id, c.owner_member_id, l.source_ref AS order_sn
                  FROM lr_card_asset c JOIN lr_fulfillment_line l ON l.id = c.fulfillment_line_id
                 WHERE c.id = ?
                """).param(cardAssetId).query(CardOrder.class).optional();
    }

    private Optional<TrackingView> view(CardOrder card) {
        return credentials.current(card.id()).filter(c -> "SHIPMENT".equals(c.kind())).map(c -> {
            Instant shippedAt = UtcColumns.fromColumn(c.deliveredAt());
            String key = settings.get(FulfillmentSettings.LOGISTICS_TRACKING_SOURCE);
            Optional<CourierTracking> source = sources.get().stream().filter(s -> s.key().equals(key)).findFirst();
            Optional<Track> track = source.flatMap(s -> s.track(new Waybill(card.orderSn(), c.logisticsRef(), c.logisticsCompany(), c.logisticsNo(), shippedAt)))
                    .filter(t -> !t.events().isEmpty());
            String message = source.isEmpty() ? UNAVAILABLE : track.isEmpty() ? NOT_YET : null;
            return new TrackingView(c.logisticsCompany(), c.logisticsNo(), DisplayTime.format(shippedAt), message,
                    track.map(Track::events).orElse(List.of()));
        });
    }
}
