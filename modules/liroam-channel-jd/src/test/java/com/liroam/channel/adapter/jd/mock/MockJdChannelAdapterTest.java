package com.liroam.channel.adapter.jd.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liroam.channel.adapter.jd.mock.MockJdChannelAdapter.MockOrderRequest;
import com.liroam.channel.spi.ExternalOrder;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.spi.ExternalOrderRef;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.channel.spi.WriteBack;
import com.liroam.channel.spi.WriteBackOutcome;
import com.liroam.common.web.UserFacingException;

/** 模拟京东：按京东开放平台订单报文形状（元、+08:00 时间、收货人手机号）造单，翻译成领域订单；结算单可注入金额不符 / 漏单 / 多单；回传可注入失败。 */
class MockJdChannelAdapterTest {

    /** 北京时间 2026-11-22 12:00。 */
    private static final Instant NOON = Instant.parse("2026-11-22T04:00:00Z");

    private final MockJdChannelAdapter jd = new MockJdChannelAdapter(Clock.fixed(NOON, ZoneOffset.UTC));

    @Test
    void wireOrderIsTranslatedToTheDomainShapeWithMaskedRawPayload() {
        String id = jd.createOrder(new MockOrderRequest("JD-SKU-1001", 2, "49.50", "13800138000")).orderId();

        List<ExternalOrder> pulled = jd.pull(NOON.minusSeconds(60));

        assertThat(pulled).singleElement().satisfies(o -> {
            assertThat(o.externalOrderId()).isEqualTo(id).matches("\\d{12}");
            assertThat(o.orderedAt()).isEqualTo(NOON);
            assertThat(o.orderedAtRaw()).isEqualTo("2026-11-22 12:00:00");
            assertThat(o.paidFen()).isEqualTo(9900);
            assertThat(o.buyerPhone()).isEqualTo("13800138000");
            assertThat(o.lines()).containsExactly(new ExternalOrderLine("1", "JD-SKU-1001", 2, 9900));
            assertThat(o.rawJson()).contains("\"orderId\":\"" + id + "\"").contains("\"orderPayment\":\"99.00\"").contains("\"jdPrice\":\"49.50\"")
                    .contains("138****8000").doesNotContain("13800138000");
        });
        assertThat(jd.pull(NOON.plusSeconds(1))).as("早于 since 的不再返回").isEmpty();
    }

    @Test
    void createOrderValidatesInput() {
        assertThatThrownBy(() -> jd.createOrder(new MockOrderRequest(" ", 1, "1.00", "13800138000"))).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> jd.createOrder(new MockOrderRequest("S", 0, "1.00", "13800138000"))).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> jd.createOrder(new MockOrderRequest("S", 1, "abc", "13800138000"))).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> jd.createOrder(new MockOrderRequest("S", 1, "1.00", ""))).isInstanceOf(UserFacingException.class);
    }

    @Test
    void statementFollowsOrdersOfTheDayAndAcceptsInjectedDifferences() {
        String a = jd.createOrder(new MockOrderRequest("S", 1, "49.00", "13800138000")).orderId();
        String b = jd.createOrder(new MockOrderRequest("S", 1, "49.00", "13800138000")).orderId();
        String c = jd.createOrder(new MockOrderRequest("S", 1, "49.00", "13800138000")).orderId();
        LocalDate day = LocalDate.of(2026, 11, 22);

        jd.adjustStatement(b, "39.00");
        jd.dropFromStatement(c);
        jd.addStatementOnly(day, "999999999999", "19.90");

        assertThat(jd.statement(day)).extracting(ExternalStatementLine::externalOrderId, ExternalStatementLine::paidFen)
                .containsExactly(tuple(a, 4900L), tuple(b, 3900L),
                        tuple("999999999999", 1990L));
        assertThat(jd.statement(day.plusDays(1))).isEmpty();
        assertThatThrownBy(() -> jd.adjustStatement("nope", "1.00")).isInstanceOf(UserFacingException.class);
    }

    @Test
    void writeBacksAreRecordedAndFaultsCanBeInjected() {
        String id = jd.createOrder(new MockOrderRequest("S", 1, "49.00", "13800138000")).orderId();
        ExternalOrderRef ref = new ExternalOrderRef(id, "1", "S");
        WriteBack wb = new WriteBack(List.of(new WriteBack.DeliveredCard("C1", "ESIM", "LPA", "LPA:1$x$y", null, null)));

        jd.failNextWriteBacks(1, false);
        assertThat(jd.writeBack(ref, wb)).isInstanceOf(WriteBackOutcome.Retryable.class);
        jd.failNextWriteBacks(1, true);
        assertThat(jd.writeBack(ref, wb)).isInstanceOf(WriteBackOutcome.Rejected.class);
        assertThat(jd.writeBack(ref, wb)).isInstanceOf(WriteBackOutcome.Ok.class);
        assertThat(jd.writeBack(new ExternalOrderRef("nope", "1", "S"), wb)).isInstanceOf(WriteBackOutcome.Rejected.class);

        assertThat(jd.orders()).singleElement().satisfies(o -> {
            assertThat(o.writeBacks()).hasSize(1);
            assertThat(o.writeBacks().getFirst()).contains("C1").doesNotContain("LPA:1$x$y");
            assertThat(o.orderState()).isEqualTo("WAIT_GOODS_RECEIVE_CONFIRM");
            assertThat(o.mobile()).isEqualTo("138****8000");
        });
    }
}
