package cn.lili.modules.distribution.serviceimpl;

import cn.lili.modules.distribution.mapper.DistributionMapper;
import cn.lili.modules.distribution.service.DistributionService;
import cn.lili.modules.distribution.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 分销管理支持类装配回归测试，验证必需依赖和禁止循环引用时的装配；外围服务使用模拟对象，不连接数据库。
 *
 * @author Bulbasaur
 * @since 2026-11-13
 */
class DistributionSupportDependencyTest {

    private static final List<Class<?>> SUPPORTS = List.of(DistributionMemberAdminSupport.class,
            DistributionMemberExportSupport.class, DistributionPromotionSupport.class, DistributionSettingSupport.class,
            DistributionRecruitSettingSupport.class, DistributionRecruitPendingTokenSupport.class,
            DistributionPromotionPosterSettingSupport.class, DistributionParentBindSupport.class,
            DistributionMemberSupport.class, DistributionInviteCardSettingSupport.class,
            DistributionGradeReferenceSupport.class, DistributionGoodsSettingSupport.class,
            DistributionBuyerCommissionSupport.class);

    /**
     * 验证分销服务和真实支持类在不同注册顺序下可通过单向依赖装配。
     *
     * @param reverse 是否反转服务及支持类注册顺序
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializesWithRequiredSupports(boolean reverse) {
        try (var context = contextWithLeafMocks()) {
            var targets = new ArrayList<Class<?>>();
            targets.add(DistributionServiceImpl.class);
            targets.addAll(SUPPORTS);
            if (reverse) {
                Collections.reverse(targets);
            }
            context.register(targets.toArray(Class<?>[]::new));
            context.refresh();
            assertThat(context.getBean(DistributionService.class)).isInstanceOf(DistributionServiceImpl.class);
        }
    }

    /** 验证管理支持类缺失时启动失败，禁止在业务执行中手动创建实例兜底。 */
    @Test
    void missingAdminSupportPreventsInitialization() {
        try (var context = contextWithLeafMocks()) {
            context.register(DistributionServiceImpl.class);
            context.register(SUPPORTS.stream().filter(type -> type != DistributionMemberAdminSupport.class)
                    .toArray(Class<?>[]::new));
            assertThatThrownBy(context::refresh).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining("distributionMemberAdminSupport");
        }
    }

    /**
     * 为分销服务和支持类注册外围依赖，保留支持类间真实装配并禁止循环引用。
     *
     * @return 尚未刷新的测试容器，调用方负责关闭
     */
    private AnnotationConfigApplicationContext contextWithLeafMocks() {
        var context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
        Set<Class<?>> targets = new HashSet<>(SUPPORTS);
        targets.add(DistributionServiceImpl.class);
        Set<Class<?>> leaves = new HashSet<>();
        leaves.add(DistributionMapper.class);
        for (Class<?> target : targets) {
            for (var field : target.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class) && !targets.contains(field.getType())) {
                    leaves.add(field.getType());
                }
            }
            if (target.getDeclaredConstructors().length == 1) {
                leaves.addAll(Arrays.asList(target.getDeclaredConstructors()[0].getParameterTypes()));
            }
        }
        leaves.removeAll(targets);
        for (Class<?> leaf : leaves) {
            context.getBeanFactory().registerSingleton("mock_" + leaf.getName(), mock(leaf));
        }
        return context;
    }
}
