package com.liroam.channel.adapter.xhs.mock;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.liroam.channel.adapter.mock.SimulatedChannel;
import com.liroam.common.safety.Simulated;

/**
 * 模拟小红书开放平台（资质未到前演示与测试用）：报文形状仿小红书订单详情——{@code orderId} 为 P 加 18 位数字、
 * {@code orderCreatedTime}/{@code orderPaidTime} 为毫秒时间戳、金额 {@code totalPayAmount} 以分计、{@code receiverPhone}、
 * {@code skuList[].skuId/skuQuantity/totalPaidAmount}、{@code orderStatus} 4 待发货 / 6 已发货。字段名与状态值【待核实】，以开放平台文档为准。
 * 存单、结算单差异与回传故障注入在 {@link SimulatedChannel}。真适配器待小红书资质与密钥。
 */
@Simulated
public class MockXhsChannelAdapter extends SimulatedChannel {

    public static final String ADAPTER_KEY = "mock-xhs";

    private static final int WAIT_DELIVER = 4;
    private static final int DELIVERED = 6;

    public MockXhsChannelAdapter(Clock clock) {
        super(clock);
    }

    @Override
    public String displayName() {
        return "模拟小红书";
    }

    @Override
    protected String orderId(long seq) {
        return "P" + (700_000_000_000_000_000L + seq);
    }

    @Override
    protected String wireState(boolean shipped) {
        return shipped ? "已发货(" + DELIVERED + ")" : "待发货(" + WAIT_DELIVER + ")";
    }

    @Override
    protected String orderedAtRaw(SimOrder order) {
        return Long.toString(order.orderedAt().toEpochMilli());
    }

    @Override
    protected Map<String, Object> wireOrder(SimOrder o, String state) {
        long millis = o.orderedAt().toEpochMilli();
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("orderId", o.orderId());
        wire.put("orderStatus", o.shipped() ? DELIVERED : WAIT_DELIVER);
        wire.put("orderCreatedTime", millis);
        wire.put("orderPaidTime", millis);
        wire.put("totalPayAmount", o.paidFen());
        wire.put("receiverPhone", mask(o.mobile()));
        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("skuId", o.skuId());
        sku.put("skuQuantity", o.qty());
        sku.put("totalPaidAmount", o.paidFen());
        wire.put("skuList", List.of(sku));
        return wire;
    }

    @Override
    protected Map<String, Object> wireStatementLine(String orderId, long paidFen) {
        return Map.of("orderId", orderId, "settleAmount", paidFen);
    }
}
