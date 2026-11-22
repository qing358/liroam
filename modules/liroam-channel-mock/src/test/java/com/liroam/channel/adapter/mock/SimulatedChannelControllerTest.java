package com.liroam.channel.adapter.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.liroam.channel.adapter.mock.SimulatedChannel.OrderRequest;
import com.liroam.channel.adapter.mock.SimulatedChannelController.FaultBody;
import com.liroam.channel.adapter.mock.SimulatedChannelController.SimulatedChannelView;
import com.liroam.channel.adapter.mock.SimulatedChannelController.StatementBody;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.UserFacingException;

/** 通用 dev 控制台：按 adapter_key 找模拟渠道，列表即已装配的模拟渠道；未知 key 报错；要运营登录。 */
class SimulatedChannelControllerTest {

    static final class Sample extends SimulatedChannel {
        Sample() {
            super(Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), ZoneOffset.UTC));
        }

        @Override
        public String displayName() {
            return "模拟样例";
        }

        @Override
        protected String orderId(long seq) {
            return "S" + seq;
        }

        @Override
        protected String wireState(boolean shipped) {
            return shipped ? "SHIPPED" : "PAID";
        }

        @Override
        protected String orderedAtRaw(SimOrder order) {
            return order.orderedAt().toString();
        }

        @Override
        protected Map<String, Object> wireOrder(SimOrder order, String state) {
            return Map.of("id", order.orderId());
        }

        @Override
        protected Map<String, Object> wireStatementLine(String orderId, long paidFen) {
            return Map.of("id", orderId);
        }
    }

    private final CurrentActor actor = mock(CurrentActor.class);
    private final Sample sample = new Sample();
    private final SimulatedChannelController console = new SimulatedChannelController(actor,
            new StaticListableBeanFactory(Map.of("mock-sample", sample)));

    @Test
    void routesByAdapterKey() {
        assertThat(console.channels().result()).containsExactly(new SimulatedChannelView("mock-sample", "模拟样例"));

        String id = console.create("mock-sample", new OrderRequest("SKU", 1, "10.00", "13800138000")).result().orderId();
        assertThat(console.adjust("mock-sample", new StatementBody(id, "9.00", null)).result())
                .singleElement().satisfies(o -> assertThat(o.statementYuan()).isEqualTo("9.00"));
        assertThat(console.drop("mock-sample", new StatementBody(id, null, null)).result())
                .singleElement().satisfies(o -> assertThat(o.statementYuan()).isNull());
        assertThat(console.faults("mock-sample", new FaultBody(1, true)).result()).isEqualTo(new FaultBody(1, true));
        assertThat(console.reset("mock-sample").result()).isEmpty();
    }

    @Test
    void rejectsUnknownKeyBadInputAndNonOperators() {
        assertThatThrownBy(() -> console.orders("mock-nope")).isInstanceOf(UserFacingException.class).hasMessage("没有模拟渠道 mock-nope");
        assertThatThrownBy(() -> console.faults("mock-sample", new FaultBody(0, false))).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> console.extra("mock-sample", new StatementBody("X", "1.00", "bad"))).isInstanceOf(UserFacingException.class)
                .hasMessage("日期格式应为 yyyy-MM-dd");

        doThrow(new UserFacingException("请先登录")).when(actor).requireOperator();
        assertThatThrownBy(console::channels).hasMessage("请先登录");
        assertThatThrownBy(() -> console.orders("mock-sample")).hasMessage("请先登录");
    }
}
