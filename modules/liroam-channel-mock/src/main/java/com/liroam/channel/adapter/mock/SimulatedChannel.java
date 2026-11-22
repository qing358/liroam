package com.liroam.channel.adapter.mock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 * 模拟渠道底座（资质未到前演示与测试用，只 dev/test 装配）：内存存单、结算单默认与订单一致并可注入金额不符 / 渠道侧漏单 / 渠道侧多单、
 * 回传可注入可重试失败或拒绝。各渠道子类只负责把内存单翻译成该渠道的报文形状（单号格式、时间、金额单位、字段名）。
 * 子类的 Bean 名即 {@code lr_channel.adapter_key}，通用 dev 控制台 {@link SimulatedChannelController} 按它找到实现。
 */
@Simulated
public abstract class SimulatedChannel implements ChannelAdapter {

    /** 结算与 dev 页展示按北京时间自然日。 */
    protected static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 后台 dev 页造单：一单一个 SKU。 */
    public record OrderRequest(String skuId, int qty, String unitPriceYuan, String mobile) {
    }

    /** dev 页看到的模拟单；手机号已打码，回传记录不含激活码。 */
    public record OrderView(String orderId, String orderState, String orderedAtText, String skuId, int qty, String paidYuan,
            String statementYuan, String mobile, List<String> writeBacks) {
    }

    /** 子类翻译报文用的只读快照；{@code mobile} 为明文，写进报文前须打码。 */
    protected record SimOrder(String orderId, Instant orderedAt, String skuId, int qty, long unitFen, String mobile, boolean shipped) {

        public long paidFen() {
            return unitFen * qty;
        }
    }

    private static final class Stored {
        final String orderId;
        final Instant orderedAt;
        final String skuId;
        final int qty;
        final long unitFen;
        final String mobile;
        boolean shipped;
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

        SimOrder snapshot() {
            return new SimOrder(orderId, orderedAt, skuId, qty, unitFen, mobile, shipped);
        }

        long statementFen() {
            return statementFen != null ? statementFen : unitFen * qty;
        }
    }

    private record StatementOnly(LocalDate day, String orderId, long paidFen) {
    }

    private final Clock clock;
    private final Map<String, Stored> orders = new LinkedHashMap<>();
    private final List<StatementOnly> statementOnly = new ArrayList<>();
    private final Deque<Boolean> writeBackFaults = new ArrayDeque<>();
    private long lastSeq;

    protected SimulatedChannel(Clock clock) {
        this.clock = clock;
    }

    /** dev 页与报错里的名字，如「模拟某渠道」。 */
    public abstract String displayName();

    /** 按序号生成该渠道格式的订单号；序号单调递增、12 位以内。 */
    protected abstract String orderId(long seq);

    /** 渠道报文里的订单状态。 */
    protected abstract String wireState(boolean shipped);

    /** 渠道原样的下单时间文本（入 {@code ExternalOrder.orderedAtRaw}）。 */
    protected abstract String orderedAtRaw(SimOrder order);

    /** 渠道订单报文；手机号用 {@link #mask} 打码后再放。 */
    protected abstract Map<String, Object> wireOrder(SimOrder order, String state);

    /** 渠道结算单的一行报文。 */
    protected abstract Map<String, Object> wireStatementLine(String orderId, long paidFen);

    public synchronized OrderView createOrder(OrderRequest request) {
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
        lastSeq = Math.max(lastSeq + 1, clock.millis() / 10 % 1_000_000_000_000L);
        Stored stored = new Stored(orderId(lastSeq), clock.instant().truncatedTo(ChronoUnit.SECONDS), request.skuId().trim(), request.qty(),
                unitFen, mobile);
        orders.put(stored.orderId, stored);
        return view(stored);
    }

    public synchronized List<OrderView> orders() {
        return orders.values().stream().map(this::view).toList();
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
        return orders.values().stream().filter(o -> !o.orderedAt.isBefore(since)).map(this::translate).toList();
    }

    @Override
    public synchronized WriteBackOutcome writeBack(ExternalOrderRef ref, WriteBack writeBack) {
        Stored order = orders.get(ref.externalOrderId());
        if (order == null) {
            return new WriteBackOutcome.Rejected("ORDER_NOT_FOUND", displayName() + "：订单不存在");
        }
        Boolean rejected = writeBackFaults.poll();
        if (rejected != null) {
            return rejected ? new WriteBackOutcome.Rejected("MOCK_REJECTED", displayName() + "：拒绝回传")
                    : new WriteBackOutcome.Retryable(displayName() + "：接口超时");
        }
        order.writeBacks.add(writeBack.cards().toString());
        order.shipped = true;
        return new WriteBackOutcome.Ok();
    }

    @Override
    public synchronized List<ExternalStatementLine> statement(LocalDate day) {
        List<ExternalStatementLine> lines = new ArrayList<>();
        for (Stored o : orders.values()) {
            if (o.inStatement && LocalDate.ofInstant(o.orderedAt, BEIJING).equals(day)) {
                lines.add(statementLine(o.orderId, o.statementFen()));
            }
        }
        statementOnly.stream().filter(s -> s.day().equals(day)).forEach(s -> lines.add(statementLine(s.orderId(), s.paidFen())));
        return lines;
    }

    private ExternalStatementLine statementLine(String orderId, long paidFen) {
        return new ExternalStatementLine(orderId, paidFen, Json.write(wireStatementLine(orderId, paidFen)));
    }

    private Stored require(String orderId) {
        Stored order = orderId == null ? null : orders.get(orderId.trim());
        if (order == null) {
            throw new UserFacingException("找不到模拟订单 " + orderId);
        }
        return order;
    }

    private ExternalOrder translate(Stored o) {
        SimOrder order = o.snapshot();
        String raw = Json.write(wireOrder(order, wireState(o.shipped)));
        return new ExternalOrder(o.orderId, o.orderedAt, orderedAtRaw(order), order.paidFen(), o.mobile,
                List.of(new ExternalOrderLine("1", o.skuId, o.qty, order.paidFen())), raw);
    }

    private OrderView view(Stored o) {
        return new OrderView(o.orderId, wireState(o.shipped), DISPLAY_TIME.format(o.orderedAt.atZone(BEIJING)), o.skuId, o.qty,
                yuan(o.unitFen * o.qty), o.inStatement ? yuan(o.statementFen()) : null, mask(o.mobile), List.copyOf(o.writeBacks));
    }

    protected static long fen(String yuan) {
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

    protected static String yuan(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    protected static String mask(String mobile) {
        return mobile.length() < 7 ? "***" : mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }
}
