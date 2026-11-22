package com.liroam.pricing.mock;

import java.math.BigDecimal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.safety.Simulated;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.UserFacingException;

/** dev/test 才有：设定 mock 汇率，再点「立即同步」演示调价建议（§15 V8/V9）。 */
@Simulated
@RestController
@RequestMapping("/manager/liroam/dev/fx-mock")
public class MockFxController {

    private final MockFxRateSource source;

    public MockFxController(MockFxRateSource source) {
        this.source = source;
    }

    public record RateBody(BigDecimal rate) {
    }

    @PostMapping
    public ApiResult<RateBody> set(@RequestBody RateBody body) {
        if (body.rate() == null || body.rate().signum() <= 0) {
            throw new UserFacingException("汇率必须大于 0");
        }
        source.setRate(body.rate());
        return ApiResult.ok(new RateBody(source.rate()));
    }
}
