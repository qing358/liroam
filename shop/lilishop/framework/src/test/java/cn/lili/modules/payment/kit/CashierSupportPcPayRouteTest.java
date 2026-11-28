package cn.lili.modules.payment.kit;

import cn.hutool.json.JSONUtil;
import cn.lili.common.enums.ClientTypeEnum;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.enums.ResultUtil;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.utils.SpringContextUtil;
import cn.lili.modules.payment.entity.enums.CashierEnum;
import cn.lili.modules.payment.entity.enums.PaymentClientEnum;
import cn.lili.modules.payment.entity.enums.PaymentMethodEnum;
import cn.lili.modules.payment.kit.dto.PayParam;
import cn.lili.modules.payment.kit.params.CashierExecute;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.dto.payment.PaymentSupportSetting;
import cn.lili.modules.system.entity.dto.payment.dto.PaymentSupportItem;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import cn.lili.modules.wallet.service.MemberWalletService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 收银台 PC 终端（电脑网站支付）路由：交给插件的 pcPay；插件未实现时回「不支持该支付方式」。
 */
class CashierSupportPcPayRouteTest {

    private ApplicationContext previous;
    private CashierSupport cashierSupport;
    private Payment alipay;

    @BeforeEach
    void setUp() {
        previous = SpringContextUtil.getApplicationContext();
        alipay = mock(Payment.class);
        when(alipay.pcPay(any(), any(), any())).thenReturn(ResultUtil.data("https://openapi.alipay.com/gateway.do"));
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton(PaymentMethodEnum.ALIPAY.getPlugin(), alipay);
        context.getBeanFactory().registerSingleton(PaymentMethodEnum.WECHAT.getPlugin(), new Payment() { });
        new SpringContextUtil().setApplicationContext(context);

        PaymentSupportItem item = new PaymentSupportItem();
        item.setClient(ClientTypeEnum.PC.name());
        item.setSupports(List.of(PaymentMethodEnum.ALIPAY.name(), PaymentMethodEnum.WECHAT.name()));
        PaymentSupportSetting supportSetting = new PaymentSupportSetting();
        supportSetting.setPaymentSupportItems(List.of(item));
        Setting setting = new Setting();
        setting.setSettingValue(JSONUtil.toJsonStr(supportSetting));
        SettingService settingService = mock(SettingService.class);
        when(settingService.get(SettingEnum.PAYMENT_SUPPORT.name())).thenReturn(setting);
        cashierSupport = new CashierSupport(mock(PaymentTransferSupport.class), List.of(mock(CashierExecute.class)),
                mock(MemberWalletService.class), settingService);
    }

    @AfterEach
    void restore() {
        new SpringContextUtil().setApplicationContext(previous);
    }

    @Test
    void routesPcClientToPluginPcPay() {
        Object result = cashierSupport.payment(PaymentMethodEnum.ALIPAY, PaymentClientEnum.PC,
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), payParam()).getResult();

        assertThat(result).isEqualTo("https://openapi.alipay.com/gateway.do");
    }

    @Test
    void pluginsWithoutPcPayAreRejected() {
        assertThatThrownBy(() -> cashierSupport.payment(PaymentMethodEnum.WECHAT, PaymentClientEnum.PC,
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), payParam()))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("resultCode", ResultCode.PAY_NOT_SUPPORT);
    }

    private static PayParam payParam() {
        PayParam payParam = new PayParam();
        payParam.setClientType(ClientTypeEnum.PC.name());
        payParam.setOrderType(CashierEnum.TRADE.name());
        payParam.setSn("T0001");
        return payParam;
    }
}
