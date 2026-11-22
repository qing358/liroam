package cn.lili.modules.order.order.serviceimpl;

import cn.lili.modules.appointment.serviceimpl.AppointmentServiceImpl;
import cn.lili.modules.appointment.support.*;
import cn.lili.modules.payment.kit.RefundSupport;
import cn.lili.modules.order.aftersale.serviceimpl.AfterSaleServiceImpl;
import cn.lili.modules.order.aftersale.support.AfterSaleQuerySupport;
import cn.lili.modules.order.aftersale.support.AfterSaleValidationSupport;
import cn.lili.modules.order.aftersale.support.AfterSaleOrderItemSyncSupport;
import cn.lili.modules.goods.serviceimpl.CardKeyServiceImpl;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.order.order.service.TradeService;
import cn.lili.modules.order.order.support.*;
import cn.lili.modules.promotion.serviceimpl.GiftCardCashServiceImpl;
import cn.lili.modules.promotion.serviceimpl.PickupCardOrderServiceImpl;
import cn.lili.modules.promotion.serviceimpl.PickupCardRuleServiceImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import cn.lili.modules.order.order.mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 交易、订单和售后服务装配测试，保留真实卡密、预约、礼品卡服务及订单和售后支持类。
 * 禁用循环引用并启用事务代理，外围存储及其他模块使用模拟对象，不连接数据库或支付渠道。
 *
 * @author Bulbasaur
 * @since 2026-11-13
 */
class TradeServiceDependencyTest {

    /**
     * 验证两种注册顺序下，交易服务可直接注入真实订单服务，并正常创建事务代理。
     *
     * @param reverse 是否反转真实服务注册顺序
     * @throws NoSuchFieldException 交易服务订单依赖字段不存在
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializesWithoutLazyOrCircularReferences(boolean reverse) throws NoSuchFieldException {
        List<Class<?>> targets = new ArrayList<>(List.of(TradeServiceImpl.class, OrderServiceImpl.class,
                OrderCreateSupport.class, OrderQuerySupport.class, OrderDeliverySupport.class,
                OrderVerificationSupport.class, OrderPintuanSupport.class, OrderStatusSupport.class,
                OrderPaymentSupport.class, PickupCardOrderServiceImpl.class, PickupCardRuleServiceImpl.class,
                GiftCardCashServiceImpl.class, CardKeyServiceImpl.class, AppointmentServiceImpl.class,
                OrderLifecycleSupport.class, ECouponOrderLifecycleSupport.class, AfterSaleServiceImpl.class,
                AfterSaleQuerySupport.class, AfterSaleValidationSupport.class, AfterSaleOrderItemSyncSupport.class,
                AppointmentWorkOrderSupport.class, AppointmentVerificationSupport.class, AppointmentStaffSupport.class,
                AppointmentSlotStockSupport.class, AppointmentNoticeSupport.class, AppointmentConfigSupport.class,
                AppointmentCheckoutSupport.class, AppointmentAfterSaleSupport.class, RefundSupport.class));
        if (reverse) {
            Collections.reverse(targets);
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            Set<Class<?>> leaves = new HashSet<>();
            leaves.add(PlatformTransactionManager.class);
            for (Class<?> target : targets) {
                for (var field : target.getDeclaredFields()) {
                    if (!field.isAnnotationPresent(Autowired.class)) {
                        continue;
                    }
                    leaves.add(field.getType() == ObjectProvider.class
                            ? (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0]
                            : field.getType());
                }
                if (target.getDeclaredConstructors().length == 1) {
                    leaves.addAll(Arrays.asList(target.getDeclaredConstructors()[0].getParameterTypes()));
                }
                if (target.getGenericSuperclass() instanceof ParameterizedType parent) {
                    leaves.add((Class<?>) parent.getActualTypeArguments()[0]);
                }
            }
            leaves.removeIf(type -> type == ApplicationEventPublisher.class
                    || targets.stream().anyMatch(type::isAssignableFrom));
            for (Class<?> leaf : leaves) {
                context.getBeanFactory().registerSingleton("mock_" + leaf.getName(), mock(leaf));
            }
            context.register(TransactionConfiguration.class);
            context.register(targets.toArray(Class<?>[]::new));
            context.refresh();

            assertThat(AopUtils.getTargetClass(context.getBean(TradeService.class))).isEqualTo(TradeServiceImpl.class);
            assertThat(AopUtils.getTargetClass(context.getBean(OrderService.class))).isEqualTo(OrderServiceImpl.class);
            assertThat(AopUtils.isAopProxy(context.getBean(TradeService.class))).isTrue();
            assertThat(AopUtils.isAopProxy(context.getBean(AppointmentAfterSaleSupport.class))).isTrue();
            assertThat(AopUtils.isAopProxy(context.getBean(AppointmentCheckoutSupport.class))).isTrue();
            assertThat(TradeServiceImpl.class.getDeclaredField("orderService").isAnnotationPresent(Lazy.class)).isFalse();
            assertThat(Arrays.stream(TradeServiceImpl.class.getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(Lazy.class))).isEmpty();
        }
    }

    /**
     * 验证订单服务缺少任一原兜底组件时拒绝启动，其余外围依赖使用模拟对象。
     *
     * @param missingSupport 本场景省略的必需组件
     */
    @ParameterizedTest
    @ValueSource(classes = {OrderCreateSupport.class, OrderQuerySupport.class, OrderPaymentSupport.class, OrderStatusSupport.class})
    void missingRequiredSupportPreventsInitialization(Class<?> missingSupport) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
            Set<Class<?>> leaves = new HashSet<>();
            leaves.add(OrderMapper.class);
            for (var field : OrderServiceImpl.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    leaves.add(field.getType());
                }
            }
            leaves.remove(missingSupport);
            leaves.remove(ApplicationEventPublisher.class);
            for (Class<?> leaf : leaves) {
                context.getBeanFactory().registerSingleton("mock_" + leaf.getName(), mock(leaf));
            }
            context.register(OrderServiceImpl.class);
            assertThatThrownBy(context::refresh).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining(missingSupport.getName());
        }
    }

    /**
     * 为测试容器开启事务代理，不调用真实事务管理器或外部支付渠道。
     *
     * @author Bulbasaur
     * @since 2026-11-13
     */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
    }
}
