package com.liroam.shell.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.event.OrderPaidEvent;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.order.order.serviceimpl.OrderServiceImpl;
import cn.lili.modules.order.order.support.OrderPaymentSupport;
import cn.lili.modules.order.trade.service.OrderLogService;

/** CT-SHELL-01：上游合并回归——OrderPaidEvent 仍在支付事务内同步发布，事件里可取到已置 PAID 的订单。 */
class ShellContractTest {

    @BeforeAll
    static void mybatisLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
    }

    @Test
    void payOrderPublishesOrderPaidEventCarryingThePaidOrder() {
        OrderService orderService = mock(OrderService.class);
        Order order = new Order();
        order.setSn("O-1");
        order.setMemberId("m1");
        order.setFlowPrice(12.5);
        order.setOrderStatus("UNPAID");
        order.setPayStatus("UNPAID");
        when(orderService.getBySn("O-1")).thenReturn(order);
        when(orderService.update(any(Wrapper.class))).thenReturn(true);
        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        var support = new OrderPaymentSupport(mock(cn.lili.modules.promotion.service.PickupCardOrderService.class),
                mock(cn.lili.modules.promotion.service.GiftCardCashService.class), mock(OrderLogService.class), publisher);

        support.payOrder("O-1", "WALLET", "R-1", 12.5, orderService);

        assertThat(events).filteredOn(OrderPaidEvent.class::isInstance).singleElement().satisfies(e -> {
            Order paid = ((OrderPaidEvent) e).getOrder();
            assertThat(paid.getSn()).isEqualTo("O-1");
            assertThat(paid.getMemberId()).isEqualTo("m1");
            assertThat(paid.getPayStatus()).isEqualTo("PAID");
        });
    }

    @Test
    void payOrderEntryPointsAreTransactional() throws NoSuchMethodException {
        Method fourArgs = OrderServiceImpl.class.getMethod("payOrder", String.class, String.class, String.class, Double.class);
        Method threeArgs = OrderServiceImpl.class.getMethod("payOrder", String.class, String.class, String.class);

        assertThat(fourArgs.getAnnotation(Transactional.class)).isNotNull();
        assertThat(threeArgs.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void listenerIsSynchronousNotAfterCommit() throws NoSuchMethodException {
        Method handler = OrderPaidFulfillmentListener.class.getMethod("onOrderPaid", OrderPaidEvent.class);

        assertThat(handler.getAnnotation(EventListener.class)).isNotNull();
        assertThat(handler.getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class)).isNull();
        assertThat(handler.getAnnotation(org.springframework.scheduling.annotation.Async.class)).isNull();
    }
}
