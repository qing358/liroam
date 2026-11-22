package cn.lili.modules.order.cart.service;

import cn.lili.cache.Cache;
import cn.lili.common.security.support.LoginFailLimitSupport;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.member.serviceimpl.MemberServiceImpl;
import cn.lili.modules.member.support.MemberAccountSupport;
import cn.lili.modules.member.support.MemberConnectSupport;
import cn.lili.modules.member.support.MemberManageSupport;
import cn.lili.modules.member.support.MemberQrCodeLoginSupport;
import cn.lili.modules.member.support.MemberSecuritySupport;
import cn.lili.modules.order.cart.support.CartCouponSupport;
import cn.lili.modules.order.cart.support.CartPromotionCheckSupport;
import cn.lili.modules.order.cart.support.CartTradeCacheSupport;
import cn.lili.modules.order.cart.support.CartTradeSettingSupport;
import cn.lili.modules.search.service.GoodsSearchService;
import cn.lili.modules.search.serviceimpl.GoodsSearchServiceImpl;
import cn.lili.modules.promotion.support.SkuPromotionQuerySupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 会员、购物车及商品搜索装配回归测试，验证真实Service与Support间的必需注入和单向依赖。
 * 搜索与促销查询组件保持真实装配，覆盖参数类型缺失导致的类反射初始化失败。
 * 外围存储、交易构造器及其他模块使用模拟对象，不连接数据库、缓存或外部服务。
 *
 * @author Bulbasaur
 * @since 2026-11-18
 */
class CartMemberSupportDependencyTest {

    private static final List<Class<?>> TARGETS = List.of(MemberServiceImpl.class, CartServiceImpl.class,
            MemberAccountSupport.class, MemberConnectSupport.class, MemberManageSupport.class,
            MemberQrCodeLoginSupport.class, MemberSecuritySupport.class, CartCouponSupport.class,
            CartPromotionCheckSupport.class, CartTradeSettingSupport.class, CartTradeCacheSupport.class,
            LoginFailLimitSupport.class, GoodsSearchServiceImpl.class, SkuPromotionQuerySupport.class);

    /**
     * 验证不同注册顺序下两个服务均可装配，扫码会话使用容器中的缓存依赖。
     *
     * @param reverse 是否反转真实组件的注册顺序
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializesWithoutCircularReferences(boolean reverse) {
        try (var context = contextWithLeafMocks()) {
            List<Class<?>> targets = new ArrayList<>(TARGETS);
            if (reverse) {
                Collections.reverse(targets);
            }
            context.register(targets.toArray(Class<?>[]::new));
            context.refresh();

            assertThat(context.getBean(CartService.class)).isInstanceOf(CartServiceImpl.class);
            assertThat(context.getBean(GoodsSearchService.class)).isInstanceOf(GoodsSearchServiceImpl.class);
            var session = context.getBean(MemberService.class).createPcSession();
            verify(context.getBean(Cache.class)).put(eq(session.getToken()), eq(session), anyLong(), any());
        }
    }

    /**
     * 验证每个必需支持组件缺失时拒绝启动，不允许Service手动创建不完整实例兜底。
     *
     * @param missingSupport 本场景省略的支持组件
     */
    @ParameterizedTest
    @ValueSource(classes = {MemberConnectSupport.class, MemberManageSupport.class, MemberQrCodeLoginSupport.class,
            MemberSecuritySupport.class, CartCouponSupport.class, CartPromotionCheckSupport.class,
            CartTradeSettingSupport.class})
    void missingSupportPreventsInitialization(Class<?> missingSupport) {
        try (var context = contextWithLeafMocks()) {
            context.register(TARGETS.stream().filter(type -> type != missingSupport).toArray(Class<?>[]::new));
            assertThatThrownBy(context::refresh)
                    .isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining(missingSupport.getName());
        }
    }

    /**
     * 按字段及单构造器依赖收集外围模拟对象，真实目标即使被测试省略也不自动补成模拟组件。
     *
     * @return 禁止循环引用且尚未刷新的测试容器，调用方负责关闭
     */
    private AnnotationConfigApplicationContext contextWithLeafMocks() {
        var context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
        Set<Class<?>> leaves = new HashSet<>();
        for (Class<?> target : TARGETS) {
            for (var field : target.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    leaves.add(field.getType());
                }
            }
            if (target.getDeclaredConstructors().length == 1) {
                leaves.addAll(Arrays.asList(target.getDeclaredConstructors()[0].getParameterTypes()));
            }
            if (target.getGenericSuperclass() instanceof ParameterizedType parent) {
                leaves.add((Class<?>) parent.getActualTypeArguments()[0]);
            }
        }
        leaves.removeIf(type -> type == ApplicationEventPublisher.class || TARGETS.stream().anyMatch(type::isAssignableFrom));
        for (Class<?> leaf : leaves) {
            context.getBeanFactory().registerSingleton("mock_" + leaf.getName(), mock(leaf));
        }
        return context;
    }
}
