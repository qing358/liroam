package com.liroam.shell.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.fulfillment.courier.CourierTracking.TrackEvent;
import com.liroam.fulfillment.courier.CourierTracking.Waybill;

import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.system.entity.vo.Traces;
import cn.lili.modules.system.service.LogisticsService;

/** 壳物流插件 → 轨迹：带收件人手机号查询；快递鸟（旧在前）与快递100（新在前）统一成新在前、精确到分钟。 */
class ShellCourierTrackingTest {

    private final LogisticsService logistics = mock(LogisticsService.class);
    private final OrderService orders = mock(OrderService.class);
    private final ShellCourierTracking tracking = new ShellCourierTracking(logistics, orders);
    private final Waybill waybill = new Waybill("O-1", "LOG-SF", "顺丰速运", "SF1", Instant.parse("2026-11-22T04:00:00Z"));

    @BeforeEach
    void order() {
        Order order = new Order();
        order.setConsigneeMobile("13800001234");
        when(orders.getBySn("O-1")).thenReturn(order);
    }

    private static Map<String, Object> trace(String time, String station) {
        Map<String, Object> m = new HashMap<>();
        m.put("AcceptTime", time);
        m.put("AcceptStation", station);
        return m;
    }

    @Test
    void mapsPluginTracesNewestFirstWithConsigneePhone() {
        when(logistics.getLogisticTrack("LOG-SF", "SF1", "13800001234")).thenReturn(new Traces("顺丰速运", "SF1",
                List.of(trace("2026-11-22 12:05:10", "已揽收"), trace("2026-11-23 09:30:00", "派送中"))));

        assertThat(tracking.key()).isEqualTo("shell");
        assertThat(tracking.track(waybill)).hasValueSatisfying(t -> assertThat(t.events()).containsExactly(
                new TrackEvent("2026-11-23 09:30", "派送中"), new TrackEvent("2026-11-22 12:05", "已揽收")));
    }

    @Test
    void missingPluginOrEmptyTracesIsEmpty() {
        when(logistics.getLogisticTrack(any(), any(), any())).thenReturn(null);
        assertThat(tracking.track(waybill)).isEmpty();

        when(logistics.getLogisticTrack(any(), any(), any())).thenReturn(new Traces("顺丰速运", "SF1", null));
        assertThat(tracking.track(waybill)).isEmpty();
    }

    @Test
    void orderWithoutPhoneStillQueries() {
        when(orders.getBySn("O-1")).thenReturn(null);
        when(logistics.getLogisticTrack(any(), any(), any())).thenReturn(null);
        tracking.track(waybill);
        verify(logistics).getLogisticTrack("LOG-SF", "SF1", "");
    }
}
