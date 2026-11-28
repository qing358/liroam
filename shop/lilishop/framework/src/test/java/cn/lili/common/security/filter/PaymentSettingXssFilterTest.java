package cn.lili.common.security.filter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端保存支付配置的过滤边界：私钥、证书内容含 Base64 与换行，必须原样到达接口。
 */
class PaymentSettingXssFilterTest {

    private static final String BODY = "{\"appId\":\"2021000000000000\",\"privateKey\":\"MIIEv+abc/def==\","
            + "\"apiclientKey\":\"-----BEGIN PRIVATE KEY-----\\nMIIE+/x==\\n-----END PRIVATE KEY-----\"}";

    @ParameterizedTest
    @ValueSource(strings = {"ALIPAY_PAYMENT", "WECHAT_PAYMENT"})
    void preservesPaymentSettingBody(String key) throws Exception {
        MockHttpServletRequest request = put("/manager/setting/setting/put/" + key);
        new XssFilter().doFilter(request, new MockHttpServletResponse(), (filtered, response) ->
                assertThat(new String(filtered.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(BODY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/manager/setting/setting/put/BASE_SETTING",
            "/manager/setting/setting/put/ALIPAY_PAYMENT/extra", "/manager/setting/setting/put/ALIPAY_PAYMENTX"})
    void keepsOtherSettingsFiltered(String path) throws Exception {
        new XssFilter().doFilter(put(path), new MockHttpServletResponse(), (filtered, response) ->
                assertThat(filtered).isInstanceOf(XssHttpServletRequestWrapper.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void keepsOtherMethodsFiltered(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/manager/setting/setting/put/ALIPAY_PAYMENT");
        new XssFilter().doFilter(request, new MockHttpServletResponse(), (filtered, response) ->
                assertThat(filtered).isInstanceOf(XssHttpServletRequestWrapper.class));
    }

    private MockHttpServletRequest put(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", path);
        request.setContentType("application/json");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
