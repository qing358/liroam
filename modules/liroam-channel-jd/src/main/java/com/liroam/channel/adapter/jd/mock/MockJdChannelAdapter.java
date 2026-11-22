package com.liroam.channel.adapter.jd.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.channel.spi.ExternalOrder;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.spi.ExternalOrderRef;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.channel.spi.WriteBack;
import com.liroam.channel.spi.WriteBackOutcome;
import com.liroam.common.safety.Simulated;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.Json;

/**
 * 模拟京东开放平台（资质未到前演示与测试用）：内存里按京东订单报文形状（{@code orderId}、{@code orderStartTime} 北京时间、
 * {@code orderPayment} 元、{@code consigneeInfo.mobile}、{@code itemInfoList[].skuId/itemTotal/jdPrice}）存单，翻译成领域订单。
 * 结算单默认与订单一致，可注入金额不符 / 渠道侧漏单 / 渠道侧多单；回传可注入可重试失败或拒绝。真适配器待京东资质与 AppKey/Secret。
 */
@Simulated
public class MockJdChannelAdapter implements ChannelAdapter {

    public static final String ADAPTER_KEY = "mock-jd";

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SHIPPED = "WAIT_GOODS_RECEIVE_CONFIRM";

    /** 后台 dev 页造单：一单一个 SKU。 */
    public record MockOrderRequest(String skuId, int qty, String unitPriceYuan, String mobile) {
    }

    /** dev 页看到的模拟单；手机号已打码，回传内容不含激活码。 */
    public record MockOrderView(String orderId, String orderState, String orderStartTime, String skuId, int itemTotal, String orderPayment,
            String statementPayment, String mobile, List<String> writeBacks) {
    }

    private static final class Stored {
        final String orderId;
        final Instant orderedAt;
        final String skuId;
        final int qty;
        final long unitFen;
        final String mobile;
        String orderState = "WAIT_SELLER_STOCK_OUT";
        Long statementFen;
        boolean inStatement = true;
        final List<String> writeBacks = new ArrayList<>();

        Stored(String orderId, Instant orderedAt, String skuId, int qty, long unitFen, String mobile) {
            this.orderId = orderId;
            this.orderedAt = orderedAt;
            this.skuId = skuId;
            this.qty = qty;
            this.unitFen = unitFen;
            this.mobile = mobile;
        }

        long paidFen() {
            return unitFen * qty;
        }
    }

    private record StatementOnly(LocalDate day, String orderId, long paidFen) {
    }

    private final Clock clock;
    private final Map<String, Stored> orders = new LinkedHashMap<>();
    private final List<StatementOnly> statementOnly = new ArrayList<>();
    private final Deque<Boolean> writeBackFaults = new ArrayDeque<>();
    private long lastOrderId;

    public MockJdChannelAdapter(Clock clock) {
        this.clock = clock;
    }

    public synchronized MockOrderView createOrder(MockOrderRequest request) {
        if (request.skuId() == null || request.skuId().isBlank()) {
            throw new UserFacingException("请填写渠道 SKU");
        }
        if (request.qty() <= 0) {
            throw new UserFacingException("数量必须大于 0");
        }
        String mobile = request.mobile() == null ? "" : request.mobile().trim();
        if (!mobile.matches("\\d{11}")) {
            throw new UserFacingException("请填写 11 位收货手机号");
        }
        long unitFen = fen(request.unitPriceYuan());
        lastOrderId = Math.max(lastOrderId + 1, clock.millis() / 10 % 1_000_000_000_000L);
        Stored stored = new Stored(Long.toString(lastOrderId), clock.instant(), request.skuId().trim(), request.qty(), unitFen, mobile);
        orders.put(stored.orderId, stored);
        return view(stored);
    }

    public synchronized List<MockOrderView> orders() {
        return orders.values().stream().map(MockJdChannelAdapter::view).toList();
    }

    /** 结算单上这单记成另一个金额（制造金额不符）。 */
    public synchronized void adjustStatement(String orderId, String paidYuan) {
        require(orderId).statementFen = fen(paidYuan);
    }

    /** 结算单不含这单（制造渠道侧漏单）。 */
    public synchronized void dropFromStatement(String orderId) {
        require(orderId).inStatement = false;
    }

    /** 结算单多出一单本站没有的（制造本站漏单）。 */
    public synchronized void addStatementOnly(LocalDate day, String orderId, String paidYuan) {
        if (orderId == null || orderId.isBlank()) {
            throw new UserFacingException("请填写订单号");
        }
        statementOnly.add(new StatementOnly(day, orderId.trim(), fen(paidYuan)));
    }

    /** 接下来 {@code count} 次回传失败：{@code rejected} 为拒绝（不重试），否则为可重试失败。 */
    public synchronized void failNextWriteBacks(int count, boolean rejected) {
        for (int i = 0; i < count; i++) {
            writeBackFaults.add(rejected);
        }
    }

    public synchronized void reset() {
        orders.clear();
        statementOnly.clear();
        writeBackFaults.clear();
    }

    @Override
    public synchronized List<ExternalOrder> pull(Instant since) {
        return orders.values().stream().filter(o -> !o.orderedAt.isBefore(since)).map(MockJdChannelAdapter::translate).toList();
    }

    @Override
    public synchronized WriteBackOutcome writeBack(ExternalOrderRef ref, WriteBack writeBack) {
        Stored order = orders.get(ref.externalOrderId());
        if (order == null) {
            return new WriteBackOutcome.Rejected("ORDER_NOT_FOUND", "模拟京东：订单不存在");
        }
        Boolean rejected = writeBackFaults.poll();
        if (rejected != null) {
            return rejected ? new WriteBackOutcome.Rejected("MOCK_REJECTED", "模拟京东：拒绝回传")
                    : new WriteBackOutcome.Retryable("模拟京东：接口超时");
        }
        order.writeBacks.add(writeBack.cards().toString());
        order.orderState = SHIPPED;
        return new WriteBackOutcome.Ok();
    }

    @Override
    public synchronized List<ExternalStatementLine> statement(LocalDate day) {
        List<ExternalStatementLine> lines = new ArrayList<>();
        for (Stored o : orders.values()) {
            if (o.inStatement && LocalDate.ofInstant(o.orderedAt, BEIJING).equals(day)) {
                long paid = o.statementFen != null ? o.statementFen : o.paidFen();
                lines.add(new ExternalStatementLine(o.orderId, paid, settleJson(o.orderId, paid)));
            }
        }
        statementOnly.stream().filter(s -> s.day().equals(day))
                .forEach(s -> lines.add(new ExternalStatementLine(s.orderId(), s.paidFen(), settleJson(s.orderId(), s.paidFen()))));
        return lines;
    }

    private Stored require(String orderId) {
        Stored order = orderId == null ? null : orders.get(orderId.trim());
        if (order == null) {
            throw new UserFacingException("找不到模拟订单 " + orderId);
        }
        return order;
    }

    private static ExternalOrder translate(Stored o) {
        String startTime = startTime(o);
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("orderId", o.orderId);
        wire.put("orderState", o.orderState);
        wire.put("orderStartTime", startTime);
        wire.put("orderTotalPrice", yuan(o.paidFen()));
        wire.put("orderPayment", yuan(o.paidFen()));
        wire.put("consigneeInfo", Map.of("mobile", mask(o.mobile)));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("skuId", o.skuId);
        item.put("itemTotal", Integer.toString(o.qty));
        item.put("jdPrice", yuan(o.unitFen));
        wire.put("itemInfoList", List.of(item));
        Instant orderedAt = LocalDateTime.parse(startTime, TIME).atZone(BEIJING).toInstant();
        return new ExternalOrder(o.orderId, orderedAt, startTime, o.paidFen(), o.mobile,
                List.of(new ExternalOrderLine("1", o.skuId, o.qty, o.paidFen())), Json.write(wire));
    }

    private static MockOrderView view(Stored o) {
        return new MockOrderView(o.orderId, o.orderState, startTime(o), o.skuId, o.qty, yuan(o.paidFen()),
                o.inStatement ? yuan(o.statementFen != null ? o.statementFen : o.paidFen()) : null, mask(o.mobile), List.copyOf(o.writeBacks));
    }

    private static String startTime(Stored o) {
        return TIME.format(o.orderedAt.atZone(BEIJING));
    }

    private static String settleJson(String orderId, long fen) {
        return Json.write(Map.of("orderId", orderId, "settleAmount", yuan(fen)));
    }

    private static long fen(String yuan) {
        try {
            BigDecimal value = new BigDecimal(yuan == null ? "" : yuan.trim());
            if (value.signum() <= 0 || value.scale() > 2) {
                throw new NumberFormatException();
            }
            return value.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new UserFacingException("金额格式不对：" + yuan + "（元，最多两位小数）");
        }
    }

    private static String yuan(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String mask(String mobile) {
        return mobile.length() < 7 ? "***" : mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }
}
