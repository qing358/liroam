package cn.lili.modules.order.aftersale.support;

import cn.lili.modules.appointment.service.AppointmentService;
import cn.lili.modules.order.aftersale.serviceimpl.AfterSaleServiceImpl;
import cn.lili.modules.order.aftersale.mapper.AfterSaleMapper;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.promotion.service.PickupCardOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Set;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 售后支持类构造器注入回归测试，验证容器装配及必需依赖缺失时的启动失败行为。
 * 外围服务使用模拟对象，不连接数据库或外部服务。
 *
 * @author Bulbasaur
 * @since 2026-11-18
 */
class AfterSaleSupportDependencyTest {

    /** 验证两个售后支持类在禁止循环引用的容器中能够通过构造器注入必需依赖。 */
    @Test
    void initializesWithRequiredDependencies() {
        try (var context = contextWithLeafMocks(null)) {
            context.registerBean(PickupCardOrderService.class, () -> mock(PickupCardOrderService.class));
            context.refresh();

            assertThat(context.getBean(AfterSaleValidationSupport.class)).isNotNull();
            assertThat(context.getBean(AfterSaleOrderItemSyncSupport.class)).isNotNull();
            assertThat(context.getBean(AfterSaleServiceImpl.class)).isNotNull();
        }
    }

    /** 验证提货卡订单服务缺失时直接拒绝启动，避免生成依赖不完整的售后校验组件。 */
    @Test
    void missingPickupCardServicePreventsInitialization() {
        try (var context = contextWithLeafMocks(null)) {
            assertThatThrownBy(context::refresh)
                    .isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining(PickupCardOrderService.class.getName());
        }
    }

    /**
     * 验证售后任一必需组件缺失时在启动阶段失败，服务不在首次请求时手动创建实例。
     *
     * @param missingSupport 本场景不注册的售后组件
     */
    @ParameterizedTest
    @ValueSource(classes = {AfterSaleQuerySupport.class, AfterSaleValidationSupport.class, AfterSaleOrderItemSyncSupport.class})
    void missingSupportPreventsServiceInitialization(Class<?> missingSupport) {
        try (var context = contextWithLeafMocks(missingSupport)) {
            context.registerBean(PickupCardOrderService.class, () -> mock(PickupCardOrderService.class));
            assertThatThrownBy(context::refresh).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining(missingSupport.getName());
        }
    }

    /**
     * 注册售后支持类与公共外围模拟服务，提货卡服务由各测试场景按需提供。
     *
     * @param missingSupport 需省略的支持组件，null表示全部注册
     * @return 尚未刷新的容器，调用方负责关闭
     */
    private AnnotationConfigApplicationContext contextWithLeafMocks(Class<?> missingSupport) {
        var context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
        List<Class<?>> supports = List.of(AfterSaleQuerySupport.class, AfterSaleValidationSupport.class,
                AfterSaleOrderItemSyncSupport.class);
        Set<Class<?>> leaves = new HashSet<>(List.of(OrderService.class, OrderItemService.class,
                AppointmentService.class, AfterSaleMapper.class));
        for (var field : AfterSaleServiceImpl.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Autowired.class)) {
                leaves.add(field.getType());
            }
        }
        leaves.removeAll(supports);
        leaves.remove(PickupCardOrderService.class);
        leaves.remove(ApplicationEventPublisher.class);
        for (Class<?> leaf : leaves) {
            context.getBeanFactory().registerSingleton("mock_" + leaf.getName(), mock(leaf));
        }
        context.register(AfterSaleServiceImpl.class);
        context.register(supports.stream().filter(type -> type != missingSupport).toArray(Class<?>[]::new));
        return context;
    }
}
