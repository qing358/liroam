package cn.lili.modules.payment.kit.plugin.alipay;

import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.payment.kit.CashierSupport;
import cn.lili.modules.payment.kit.dto.PayParam;
import cn.lili.modules.payment.kit.params.dto.CashierParam;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import com.alipay.api.domain.AlipayTradePagePayModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PC 端支付宝走电脑网站支付（alipay.trade.page.pay）：不依赖「当面付」签约，返回跳转支付宝收银台的地址。
 */
class AliPayPluginPcPayTest {

    private static final String API = "http://pay.example.com";

    private AlipayTradePagePayModel model;
    private String notifyUrl;
    private String returnUrl;
    private AliPayPlugin plugin;
    private SettingService settingService;

    @BeforeEach
    void setUp() {
        plugin = new AliPayPlugin() {
            @Override
            String pagePayUrl(AlipayTradePagePayModel m, String notify, String back) {
                model = m;
                notifyUrl = notify;
                returnUrl = back;
                return "https://openapi.alipay.com/gateway.do?method=alipay.trade.page.pay";
            }
        };
        CashierSupport cashierSupport = mock(CashierSupport.class);
        CashierParam cashierParam = new CashierParam();
        cashierParam.setPrice(25.9);
        cashierParam.setTitle("轻游");
        cashierParam.setDetail("日本 eSIM 7天3GB");
        when(cashierSupport.cashierParam(any())).thenReturn(cashierParam);
        settingService = mock(SettingService.class);
        when(settingService.get(SettingEnum.ALIPAY_PAYMENT.name())).thenReturn(setting("{\"callbackUrl\":\"" + API + "\"}"));
        ReflectionTestUtils.setField(plugin, "cashierSupport", cashierSupport);
        ReflectionTestUtils.setField(plugin, "settingService", settingService);
    }

    @Test
    void returnsPagePayUrlWithNotifyAndBuyerReturnAddress() {
        when(settingService.get(SettingEnum.BASE_SETTING.name()))
                .thenReturn(setting("{\"staticPageAddress\":\"https://www.example.com/\"}"));

        ResultMessage<Object> result = plugin.pcPay(new MockHttpServletRequest(), new MockHttpServletResponse(), payParam());

        assertThat(result.getResult()).isEqualTo("https://openapi.alipay.com/gateway.do?method=alipay.trade.page.pay");
        assertThat(model.getProductCode()).isEqualTo("FAST_INSTANT_TRADE_PAY");
        assertThat(model.getTotalAmount()).isEqualTo("25.9");
        assertThat(model.getSubject()).isEqualTo("日本 eSIM 7天3GB");
        assertThat(model.getOutTradeNo()).isNotBlank();
        assertThat(model.getPassbackParams()).isNotBlank();
        assertThat(notifyUrl).isEqualTo(API + "/buyer/payment/cashier/notify/ALIPAY");
        assertThat(returnUrl).isEqualTo("https://www.example.com/payDone?orderType=RECHARGE");
    }

    @Test
    void fallsBackToCashierCallbackWhenBuyerAddressMissing() {
        when(settingService.get(SettingEnum.BASE_SETTING.name())).thenReturn(setting("{}"));

        plugin.pcPay(new MockHttpServletRequest(), new MockHttpServletResponse(), payParam());

        assertThat(returnUrl).isEqualTo(API + "/buyer/payment/cashier/callback/ALIPAY");
    }

    private static PayParam payParam() {
        PayParam payParam = new PayParam();
        payParam.setSn("R0001");
        payParam.setOrderType("RECHARGE");
        payParam.setClientType("PC");
        return payParam;
    }

    private static Setting setting(String value) {
        Setting setting = new Setting();
        setting.setSettingValue(value);
        return setting;
    }
}
