package cn.lili.modules.payment.kit;

import cn.lili.common.enums.ClientTypeEnum;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.modules.payment.entity.enums.CashierEnum;
import cn.lili.modules.payment.entity.enums.PaymentClientEnum;
import cn.lili.modules.payment.entity.enums.PaymentMethodEnum;
import cn.lili.modules.payment.kit.dto.PayParam;
import cn.lili.modules.payment.kit.params.CashierExecute;
import cn.lili.modules.payment.kit.plugin.wechat.WechatConfigSupport;
import cn.lili.modules.payment.kit.plugin.wechat.WechatOrderQuerySupport;
import cn.lili.modules.payment.kit.plugin.wechat.WechatRefundSupport;
import cn.lili.modules.payment.kit.plugin.wechat.WechatTransferSupport;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.dto.payment.PaymentSupportSetting;
import cn.lili.modules.system.entity.dto.payment.dto.PaymentSupportItem;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import cn.lili.modules.wallet.service.MemberWalletService;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * 收银台支付方式后台开关安全校验测试
 *
 * @author Bulbasaur
 * @since 2026-11-11
 */
class CashierSupportPaymentMethodCheckTest {

    private static final List<Class<?>> PAYMENT_SUPPORTS = List.of(CashierSupport.class, RefundSupport.class,
            WechatConfigSupport.class, WechatOrderQuerySupport.class, WechatRefundSupport.class, WechatTransferSupport.class);

    private CashierSupport cashierSupport;
    private SettingService settingService;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        cashierSupport = new CashierSupport(mock(PaymentTransferSupport.class), List.of(mock(CashierExecute.class)),
                mock(MemberWalletService.class), settingService);
    }

    /** 验证支付支持组件可一同装配，收银台按订单类型选择处理器，避免遗漏列表中的其他入口。 */
    @Test
    void initializesPaymentSupportsAndDispatchesMatchingHandler() {
        try (var context = contextWithPaymentMocks(false)) {
            CashierExecute recharge = mock(CashierExecute.class);
            CashierExecute order = mock(CashierExecute.class);
            context.getBeanFactory().registerSingleton("rechargeCashier", recharge);
            context.getBeanFactory().registerSingleton("orderCashier", order);
            when(recharge.cashierEnum()).thenReturn(CashierEnum.RECHARGE);
            when(order.cashierEnum()).thenReturn(CashierEnum.ORDER);
            PayParam request = new PayParam();
            request.setOrderType(CashierEnum.ORDER.name());
            when(order.paymentResult(request)).thenReturn(true);
            context.refresh();

            assertThat(context.getBean(CashierSupport.class).paymentResult(request)).isTrue();
            verify(order).paymentResult(request);
            verify(recharge, never()).paymentResult(request);
        }
    }

    /** 验证没有任何收银处理器时拒绝启动，保留原必需集合注入的装配约束。 */
    @Test
    void missingCashierHandlersPreventsInitialization() {
        try (var context = contextWithPaymentMocks(false)) {
            assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
                    .hasMessageContaining("cashierSupport");
        }
    }

    /** 验证支付系统设置服务缺失时拒绝装配，不在支付或退款请求中才暴露空依赖。 */
    @Test
    void missingSettingsPreventsInitialization() {
        try (var context = contextWithPaymentMocks(true)) {
            context.getBeanFactory().registerSingleton("orderCashier", mock(CashierExecute.class));
            assertThatThrownBy(context::refresh).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining(SettingService.class.getName());
        }
    }

    /**
     * 注册六个真实支付组件及外围模拟依赖，处理器由各场景提供，不初始化支付SDK或外部渠道。
     *
     * @param missingSettings 是否省略系统设置服务
     * @return 禁止循环引用且尚未刷新的测试容器，调用方负责关闭
     */
    private AnnotationConfigApplicationContext contextWithPaymentMocks(boolean missingSettings) {
        var context = new AnnotationConfigApplicationContext();
        context.getDefaultListableBeanFactory().setAllowCircularReferences(false);
        Set<Class<?>> leaves = new HashSet<>();
        for (Class<?> target : PAYMENT_SUPPORTS) {
            for (var field : target.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class) && field.getType() != List.class) {
                    leaves.add(field.getType());
                }
            }
            if (target.getDeclaredConstructors().length == 1) {
                leaves.addAll(Arrays.asList(target.getDeclaredConstructors()[0].getParameterTypes()));
            }
        }
        leaves.removeAll(PAYMENT_SUPPORTS);
        leaves.remove(List.class);
        if (missingSettings) {
            leaves.remove(SettingService.class);
        }
        for (Class<?> leaf : leaves) {
            context.getBeanFactory().registerSingleton("mock_" + leaf.getName(), mock(leaf));
        }
        context.register(PAYMENT_SUPPORTS.toArray(Class<?>[]::new));
        return context;
    }

    /**
     * 后台关闭余额支付时，直接调用支付接口必须被服务端拦截并抛出 PAY_NOT_SUPPORT
     */
    @Test
    @DisplayName("后台关闭余额支付时直接调用接口必须拦截")
    void paymentRejectsDisabledWalletMethod() {
        // 配置 H5 客户端仅开启 WECHAT 和 ALIPAY，不开启 WALLET
        PaymentSupportSetting supportSetting = new PaymentSupportSetting();
        PaymentSupportItem item = new PaymentSupportItem();
        item.setClient(ClientTypeEnum.H5.name());
        item.setSupports(List.of(PaymentMethodEnum.WECHAT.name(), PaymentMethodEnum.ALIPAY.name()));
        supportSetting.setPaymentSupportItems(List.of(item));

        Setting setting = new Setting();
        setting.setSettingValue(JSONUtil.toJsonStr(supportSetting));
        when(settingService.get(SettingEnum.PAYMENT_SUPPORT.name())).thenReturn(setting);

        PayParam payParam = new PayParam();
        payParam.setClientType(ClientTypeEnum.H5.name());
        payParam.setOrderType(CashierEnum.ORDER.name());
        payParam.setSn("O12345");

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        // 尝试绕过前端界面直接调用余额支付
        assertThatThrownBy(() -> cashierSupport.payment(
                PaymentMethodEnum.WALLET,
                PaymentClientEnum.H5,
                request,
                response,
                payParam
        )).isInstanceOf(ServiceException.class)
          .hasFieldOrPropertyWithValue("resultCode", ResultCode.PAY_NOT_SUPPORT);
    }

    /**
     * 付费会员购买若使用非白名单支付方式，同样必须被服务端拦截
     */
    @Test
    @DisplayName("付费会员使用非允许支付方式直接调用接口必须拦截")
    void paymentRejectsUnsupportedMethodForPaidMember() {
        // 配置 PC 客户端开启 BANK_TRANSFER, WECHAT
        PaymentSupportSetting supportSetting = new PaymentSupportSetting();
        PaymentSupportItem item = new PaymentSupportItem();
        item.setClient(ClientTypeEnum.PC.name());
        item.setSupports(List.of(PaymentMethodEnum.BANK_TRANSFER.name(), PaymentMethodEnum.WECHAT.name()));
        supportSetting.setPaymentSupportItems(List.of(item));

        Setting setting = new Setting();
        setting.setSettingValue(JSONUtil.toJsonStr(supportSetting));
        when(settingService.get(SettingEnum.PAYMENT_SUPPORT.name())).thenReturn(setting);

        PayParam payParam = new PayParam();
        payParam.setClientType(ClientTypeEnum.PC.name());
        payParam.setOrderType(CashierEnum.PAID_MEMBER.name());
        payParam.setSn("PM12345");

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        // 付费会员禁止使用 BANK_TRANSFER
        assertThatThrownBy(() -> cashierSupport.payment(
                PaymentMethodEnum.BANK_TRANSFER,
                PaymentClientEnum.NATIVE,
                request,
                response,
                payParam
        )).isInstanceOf(ServiceException.class)
          .hasFieldOrPropertyWithValue("resultCode", ResultCode.PAY_NOT_SUPPORT);
    }
}
