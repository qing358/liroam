package com.liroam.channel.adapter.jd.mock;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.liroam.channel.adapter.mock.SimulatedChannel;
import com.liroam.common.safety.Simulated;

/**
 * 模拟京东开放平台（资质未到前演示与测试用）：按京东订单报文形状（{@code orderId} 12 位数字、{@code orderStartTime} 北京时间、
 * {@code orderPayment} 元、{@code consigneeInfo.mobile}、{@code itemInfoList[].skuId/itemTotal/jdPrice}）翻译内存单。
 * 存单、结算单差异与回传故障注入在 {@link SimulatedChannel}。真适配器待京东资质与 AppKey/Secret。
 */
@Simulated
public class MockJdChannelAdapter extends SimulatedChannel {

    public static final String ADAPTER_KEY = "mock-jd";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MockJdChannelAdapter(Clock clock) {
        super(clock);
    }

    @Override
    public String displayName() {
        return "模拟京东";
    }

    @Override
    protected String orderId(long seq) {
        return Long.toString(seq);
    }

    @Override
    protected String wireState(boolean shipped) {
        return shipped ? "WAIT_GOODS_RECEIVE_CONFIRM" : "WAIT_SELLER_STOCK_OUT";
    }

    @Override
    protected String orderedAtRaw(SimOrder order) {
        return TIME.format(order.orderedAt().atZone(BEIJING));
    }

    @Override
    protected Map<String, Object> wireOrder(SimOrder o, String state) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("orderId", o.orderId());
        wire.put("orderState", state);
        wire.put("orderStartTime", orderedAtRaw(o));
        wire.put("orderTotalPrice", yuan(o.paidFen()));
        wire.put("orderPayment", yuan(o.paidFen()));
        wire.put("consigneeInfo", Map.of("mobile", mask(o.mobile())));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("skuId", o.skuId());
        item.put("itemTotal", Integer.toString(o.qty()));
        item.put("jdPrice", yuan(o.unitFen()));
        wire.put("itemInfoList", List.of(item));
        return wire;
    }

    @Override
    protected Map<String, Object> wireStatementLine(String orderId, long paidFen) {
        return Map.of("orderId", orderId, "settleAmount", yuan(paidFen));
    }
}
