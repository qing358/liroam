package cn.lili.modules.payment.kit.plugin.alipay;

import com.alipay.api.AlipayConstants;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付宝网关按请求里的 SDK 版本判断是否支持 openid 应用；低于 4.35.37 时所有接口返回 unsupported-sdk-version。
 */
class AlipaySdkVersionTest {

    private static final int[] OPENID_MIN_VERSION = {4, 35, 37};

    @Test
    void sdkVersionSentToGatewaySupportsOpenid() {
        Matcher matcher = Pattern.compile("alipay-sdk-java-(\\d+)\\.(\\d+)\\.(\\d+)").matcher(AlipayConstants.SDK_VERSION);
        assertThat(matcher.lookingAt()).as("SDK 版本号：%s", AlipayConstants.SDK_VERSION).isTrue();
        int[] actual = {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))};
        assertThat(compare(actual, OPENID_MIN_VERSION)).as("SDK 版本号：%s", AlipayConstants.SDK_VERSION).isGreaterThanOrEqualTo(0);
    }

    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return Integer.compare(left[i], right[i]);
            }
        }
        return 0;
    }
}
