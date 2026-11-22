package cn.lili.modules.order.order.serviceimpl;

import cn.lili.common.event.TransactionCommitSendMQEvent;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.message.LiliMessageTemplate;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dos.OrderItem;
import cn.lili.modules.order.order.entity.enums.OrderStatusEnum;
import cn.lili.modules.order.order.entity.enums.OrderTypeEnum;
import cn.lili.modules.order.order.entity.enums.PayStatusEnum;
import cn.lili.modules.order.order.mapper.OrderMapper;
import cn.lili.modules.order.order.mapper.OrderItemMapper;
import cn.lili.modules.order.order.support.ECouponOrderLifecycleSupport;
import cn.lili.modules.order.order.support.OrderPaymentSupport;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E_COUPON专用完成/取消CAS单元测试，通过真实生命周期组件和模拟Mapper验证状态竞争与收尾。
 * 条件更新结果由模拟对象控制，不代表数据库并发集成验证。
 *
 * @author Mike
 * @date 2026-08-03
 */
class OrderServiceImplECouponCasTest {

    private OrderServiceImpl orderService;
    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private ApplicationEventPublisher applicationEventPublisher;

    /**
     * 初始化当前测试使用的MyBatis-Plus实体元数据。
     */
    @BeforeAll
    static void initMybatisEntityCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    /**
     * 初始化当前用例的模拟依赖及测试数据。
     */
    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl();
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        SettingService settings = mock(SettingService.class);
        RocketmqCustomProperties properties = mock(RocketmqCustomProperties.class);
        when(properties.getOrderTopic()).thenReturn("order-topic");
        Setting orderSetting = new Setting();
        orderSetting.setSettingValue("{\"closeComplaint\":1}");
        when(settings.get(SettingEnum.ORDER_SETTING.name())).thenReturn(orderSetting);
        ReflectionTestUtils.setField(orderService, "eCouponOrderLifecycleSupport", new ECouponOrderLifecycleSupport(
                orderMapper, orderItemMapper, settings, mock(OrderPaymentSupport.class), applicationEventPublisher,
                properties, mock(LiliMessageTemplate.class)));
    }

    /** PAID 态 E_COUPON 可 CAS 完成并触发完成副作用。 */
    @Test
    void systemCompleteECouponCompletesPaidECouponOrder() {
        Order order = paidECoupon("O001");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertThat(orderService.systemCompleteECoupon("O001")).isTrue();
        verify(orderMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(applicationEventPublisher).publishEvent(any(TransactionCommitSendMQEvent.class));
    }

    /** 已完成订单幂等返回 true。 */
    @Test
    void systemCompleteECouponIsIdempotentWhenAlreadyCompleted() {
        Order order = paidECoupon("O001");
        order.setOrderStatus(OrderStatusEnum.COMPLETED.name());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        assertThat(orderService.systemCompleteECoupon("O001")).isTrue();
        verify(orderMapper, never()).update(isNull(), any(Wrapper.class));
    }

    /** 已取消订单不可再完成。 */
    @Test
    void systemCompleteECouponRejectsCancelledOrder() {
        Order order = paidECoupon("O001");
        order.setOrderStatus(OrderStatusEnum.CANCELLED.name());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        assertThat(orderService.systemCompleteECoupon("O001")).isFalse();
        verify(orderMapper, never()).update(isNull(), any(Wrapper.class));
    }

    /** 非 E_COUPON 订单拒绝完成 CAS。 */
    @Test
    void systemCompleteECouponRejectsVirtualOrder() {
        Order order = paidECoupon("O001");
        order.setOrderType(OrderTypeEnum.VIRTUAL.name());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        assertThat(orderService.systemCompleteECoupon("O001")).isFalse();
    }

    /** PAID 态 E_COUPON 可 CAS 取消。 */
    @Test
    void systemCancelECouponCancelsPaidECouponOrder() {
        Order order = paidECoupon("O001");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertThat(orderService.systemCancelECoupon("O001", "test", true)).isTrue();
        verify(orderMapper).update(isNull(), any(Wrapper.class));
    }

    /** 已取消订单幂等返回 true。 */
    @Test
    void systemCancelECouponIsIdempotentWhenAlreadyCancelled() {
        Order order = paidECoupon("O001");
        order.setOrderStatus(OrderStatusEnum.CANCELLED.name());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        assertThat(orderService.systemCancelECoupon("O001", "test", true)).isTrue();
        verify(orderMapper, never()).update(isNull(), any(Wrapper.class));
    }

    /** 已完成订单不可取消。 */
    @Test
    void systemCancelECouponRejectsCompletedOrder() {
        Order order = paidECoupon("O001");
        order.setOrderStatus(OrderStatusEnum.COMPLETED.name());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        assertThat(orderService.systemCancelECoupon("O001", "test", true)).isFalse();
        verify(orderMapper, never()).update(isNull(), any(Wrapper.class));
    }

    /**
     * TS-58：模拟完成与取消CAS并发竞争，仅一方成功，完成时间更新不计入状态竞争次数。
     */
    @Test
    void completeAndCancelRaceOnlyOneCasUpdateSucceeds() throws Exception {
        when(orderMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> paidECoupon("O-RACE"));

        AtomicInteger casUpdates = new AtomicInteger(0);
        doAnswer(invocation -> {
            LambdaUpdateWrapper<Order> update = invocation.getArgument(1);
            if (!update.getSqlSet().contains("order_status")) {
                return 1;
            }
            if (casUpdates.incrementAndGet() == 1) {
                return 1;
            }
            return 0;
        }).when(orderMapper).update(isNull(), any(Wrapper.class));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> completeFuture = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return orderService.systemCompleteECoupon("O-RACE");
            });
            Future<Boolean> cancelFuture = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return orderService.systemCancelECoupon("O-RACE", "user cancel", true);
            });
            start.countDown();

            boolean completeOk = completeFuture.get(10, TimeUnit.SECONDS);
            boolean cancelOk = cancelFuture.get(10, TimeUnit.SECONDS);

            assertThat(completeOk ^ cancelOk).isTrue();
            int expectedUpdates = completeOk ? 3 : 2;
            assertThat(casUpdates.get()).isEqualTo(2);
            verify(orderMapper, times(expectedUpdates)).update(isNull(), any(Wrapper.class));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 构造已付款的卡密订单。
     *
     * @param sn 订单编号
     * @return 已付款的卡密订单
     */
    private Order paidECoupon(String sn) {
        Order order = new Order();
        order.setSn(sn);
        order.setOrderType(OrderTypeEnum.E_COUPON.name());
        order.setOrderStatus(OrderStatusEnum.PAID.name());
        order.setPayStatus(PayStatusEnum.PAID.name());
        order.setMemberId("m1");
        order.setFlowPrice(10D);
        order.setPaymentMethod("WALLET");
        return order;
    }
}
