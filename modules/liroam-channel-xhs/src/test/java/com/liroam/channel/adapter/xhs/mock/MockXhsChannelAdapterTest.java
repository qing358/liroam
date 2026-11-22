package com.liroam.channel.adapter.xhs.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liroam.channel.adapter.mock.SimulatedChannel.OrderRequest;
import com.liroam.channel.spi.ExternalOrder;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.spi.ExternalOrderRef;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.channel.spi.WriteBack;
import com.liroam.channel.spi.WriteBackOutcome;

/** 模拟小红书：报文金额是分、时间是毫秒时间戳、单号带 P 前缀（形状【待核实】），翻译成与京东相同的领域订单。 */
class MockXhsChannelAdapterTest {

    /** 北京时间 2026-11-22 12:00。 */
    private static final Instant NOON = Instant.parse("2026-11-22T04:00:00Z");

    private final MockXhsChannelAdapter xhs = new MockXhsChannelAdapter(Clock.fixed(NOON, ZoneOffset.UTC));

    @Test
    void wireOrderIsTranslatedToTheDomainShapeWithMaskedRawPayload() {
        String id = xhs.createOrder(new OrderRequest("XHS-SKU-1001", 2, "49.50", "13800138000")).orderId();

        List<ExternalOrder> pulled = xhs.pull(NOON.minusSeconds(60));

        assertThat(pulled).singleElement().satisfies(o -> {
            assertThat(o.externalOrderId()).isEqualTo(id).matches("P\\d{18}");
            assertThat(o.orderedAt()).isEqualTo(NOON);
            assertThat(o.orderedAtRaw()).isEqualTo(Long.toString(NOON.toEpochMilli()));
            assertThat(o.paidFen()).isEqualTo(9900);
            assertThat(o.buyerPhone()).isEqualTo("13800138000");
            assertThat(o.lines()).containsExactly(new ExternalOrderLine("1", "XHS-SKU-1001", 2, 9900));
            assertThat(o.rawJson()).contains("\"orderId\":\"" + id + "\"").contains("\"totalPayAmount\":9900").contains("\"orderStatus\":4")
                    .contains("\"skuQuantity\":2").contains("138****8000").doesNotContain("13800138000");
        });
    }

    @Test
    void writeBackMarksShippedAndStatementIsInFen() {
        String id = xhs.createOrder(new OrderRequest("S", 1, "49.00", "13800138000")).orderId();
        xhs.writeBack(new ExternalOrderRef(id, "1", "S"),
                new WriteBack(List.of(new WriteBack.DeliveredCard("C1", "ESIM", "LPA", "LPA:1$x$y", null, null))));

        assertThat(xhs.pull(NOON.minusSeconds(1))).singleElement().satisfies(o -> assertThat(o.rawJson()).contains("\"orderStatus\":6"));
        assertThat(xhs.statement(LocalDate.of(2026, 11, 22))).extracting(ExternalStatementLine::externalOrderId, ExternalStatementLine::paidFen)
                .containsExactly(tuple(id, 4900L));
        assertThat(xhs.statement(LocalDate.of(2026, 11, 22)).getFirst().rawJson()).contains("\"settleAmount\":4900");
        assertThat(xhs.writeBack(new ExternalOrderRef("nope", "1", "S"), new WriteBack(List.of()))).isInstanceOf(WriteBackOutcome.Rejected.class)
                .hasToString("Rejected[code=ORDER_NOT_FOUND, message=模拟小红书：订单不存在]");
    }
}
