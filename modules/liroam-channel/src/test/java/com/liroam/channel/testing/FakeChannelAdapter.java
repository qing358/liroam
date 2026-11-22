package com.liroam.channel.testing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.channel.spi.ExternalOrder;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.spi.ExternalOrderRef;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.channel.spi.WriteBack;
import com.liroam.channel.spi.WriteBackOutcome;

/** 脚本化的渠道：预置订单与结算单、记录拉单的 {@code since} 与每次回传、可排队回传结果。 */
public final class FakeChannelAdapter implements ChannelAdapter {

    public record WriteBackCall(ExternalOrderRef ref, WriteBack writeBack) {
    }

    private final List<ExternalOrder> orders = new ArrayList<>();
    private final Map<LocalDate, List<ExternalStatementLine>> statements = new HashMap<>();
    private final Deque<WriteBackOutcome> outcomes = new ArrayDeque<>();
    public final List<Instant> pulls = new ArrayList<>();
    public final List<WriteBackCall> writeBacks = new ArrayList<>();

    public synchronized void reset() {
        orders.clear();
        statements.clear();
        outcomes.clear();
        pulls.clear();
        writeBacks.clear();
    }

    public synchronized ExternalOrder addOrder(String orderId, Instant orderedAt, String phone, ExternalOrderLine... lines) {
        long paid = 0;
        for (ExternalOrderLine l : lines) {
            paid += l.paidFen();
        }
        ExternalOrder order = new ExternalOrder(orderId, orderedAt, orderedAt.toString(), paid, phone, List.of(lines),
                "{\"orderId\":\"" + orderId + "\",\"mobile\":\"" + phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) + "\"}");
        orders.add(order);
        return order;
    }

    public synchronized void statement(LocalDate day, ExternalStatementLine... lines) {
        statements.put(day, List.of(lines));
    }

    /** 接下来的回传依次返回这些结果，用完后回到成功。 */
    public synchronized void nextWriteBacks(WriteBackOutcome... results) {
        outcomes.addAll(List.of(results));
    }

    @Override
    public synchronized List<ExternalOrder> pull(Instant since) {
        pulls.add(since);
        return List.copyOf(orders);
    }

    @Override
    public synchronized WriteBackOutcome writeBack(ExternalOrderRef ref, WriteBack writeBack) {
        writeBacks.add(new WriteBackCall(ref, writeBack));
        WriteBackOutcome next = outcomes.poll();
        return next != null ? next : new WriteBackOutcome.Ok();
    }

    @Override
    public synchronized List<ExternalStatementLine> statement(LocalDate day) {
        return statements.getOrDefault(day, List.of());
    }
}
