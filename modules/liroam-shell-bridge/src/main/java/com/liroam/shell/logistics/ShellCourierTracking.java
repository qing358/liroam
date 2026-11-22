package com.liroam.shell.logistics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.liroam.fulfillment.courier.CourierTracking;

import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.system.entity.vo.Traces;
import cn.lili.modules.system.service.LogisticsService;

/**
 * 物流轨迹走壳物流插件（后台「物流设置」选快递鸟 / 快递100 并填账号）；顺丰等需收件人手机号后四位校验，取壳订单收件人手机。
 * 两家插件轨迹顺序相反（快递鸟旧在前、快递100 新在前），这里按时间统一成新在前。
 */
public class ShellCourierTracking implements CourierTracking {

    public static final String KEY = "shell";

    private final LogisticsService logistics;
    private final OrderService orders;

    public ShellCourierTracking(LogisticsService logistics, OrderService orders) {
        this.logistics = logistics;
        this.orders = orders;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Optional<Track> track(Waybill waybill) {
        Order order = orders.getBySn(waybill.orderSn());
        String phone = order == null || order.getConsigneeMobile() == null ? "" : order.getConsigneeMobile();
        Traces traces = logistics.getLogisticTrack(waybill.logisticsRef(), waybill.logisticsNo(), phone);
        if (traces == null || traces.getTraces() == null || traces.getTraces().isEmpty()) {
            return Optional.empty();
        }
        List<TrackEvent> events = traces.getTraces().stream()
                .map(t -> new TrackEvent(minutes(text(t, "AcceptTime")), text(t, "AcceptStation")))
                .sorted(Comparator.comparing(TrackEvent::timeText, Comparator.nullsFirst(Comparator.<String>naturalOrder())).reversed())
                .toList();
        return Optional.of(new Track(events));
    }

    private static String text(Map<?, ?> trace, String key) {
        return Objects.toString(trace.get(key), null);
    }

    /** {@code yyyy-MM-dd HH:mm:ss} → {@code yyyy-MM-dd HH:mm}，与本站时间展示一致。 */
    private static String minutes(String time) {
        return time != null && time.length() == 19 ? time.substring(0, 16) : time;
    }
}
