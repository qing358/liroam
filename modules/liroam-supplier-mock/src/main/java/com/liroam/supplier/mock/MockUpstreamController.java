package com.liroam.supplier.mock;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.safety.Simulated;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.UserFacingException;

/** dev/test 才有：在模拟上游上推「用户用了多少」，再等轮询或打开卡详情刷新，演示用量镜像（流量、语音、短信）。 */
@Simulated
@RestController
@RequestMapping("/manager/liroam/dev/mock-upstream")
public class MockUpstreamController {

    private final MockUpstreamConsole console;

    public MockUpstreamController(MockUpstreamConsole console) {
        this.console = console;
    }

    /** 字段为空的维度不改。 */
    public record UsageBody(String adapterKey, String upstreamRef, Long usedBytes, Long voiceUsedSeconds, Integer smsUsed) {
    }

    @PostMapping("/usage")
    public ApiResult<MockUpstreamConsole.BasePlan> usage(@RequestBody UsageBody body) {
        if (body.adapterKey() == null || body.upstreamRef() == null) {
            throw new UserFacingException("请填写模拟供应商与上游引用");
        }
        try {
            MockUpstreamConsole.BasePlan plan = null;
            if (body.usedBytes() != null) {
                plan = console.activate(body.adapterKey(), body.upstreamRef(), body.usedBytes());
            }
            if (body.voiceUsedSeconds() != null || body.smsUsed() != null) {
                plan = console.useCalls(body.adapterKey(), body.upstreamRef(), body.voiceUsedSeconds(), body.smsUsed());
            }
            if (plan == null) {
                throw new UserFacingException("至少填一项用量");
            }
            return ApiResult.ok(plan);
        } catch (IllegalArgumentException e) {
            throw new UserFacingException(e.getMessage());
        }
    }
}
