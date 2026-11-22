package com.liroam.supplier.mock;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.safety.Simulated;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.UserFacingException;

/**
 * dev/test 才有：在模拟上游上推「用户用了多少」，再等轮询或打开卡详情刷新，演示用量镜像（流量、语音、短信）；
 * 以及给模拟供应商注入接下来 N 次调用的故障，做供应商故障演练（见 docs/ops-runbook.md）。
 */
@Simulated
@RestController
@RequestMapping("/manager/liroam/dev/mock-upstream")
public class MockUpstreamController {

    private static final Set<String> SUPPLIERS = Set.of(MockShapeAAdapter.ADAPTER_KEY, MockShapeBAdapter.ADAPTER_KEY);
    private static final int MAX_FAULTS = 50;

    private final MockUpstreamConsole console;
    private final MockFaults faults;

    public MockUpstreamController(MockUpstreamConsole console, MockFaults faults) {
        this.console = console;
        this.faults = faults;
    }

    /** operation / failure 取 {@link MockFaults.Operation} / {@link MockFaults.Failure} 的名字。 */
    public record FaultBody(String adapterKey, String operation, String failure, Integer count) {
    }

    @PostMapping("/faults")
    public ApiResult<Integer> injectFaults(@RequestBody FaultBody body) {
        String supplier = supplier(body);
        MockFaults.Operation op = parse(MockFaults.Operation.class, body.operation(), "操作");
        MockFaults.Failure failure = parse(MockFaults.Failure.class, body.failure(), "故障");
        int count = body.count() == null ? 1 : body.count();
        if (count < 1 || count > MAX_FAULTS) {
            throw new UserFacingException("故障次数须在 1–" + MAX_FAULTS + " 之间");
        }
        for (int i = 0; i < count; i++) {
            faults.failNext(supplier, op, failure);
        }
        return ApiResult.ok(count);
    }

    /** 清掉该模拟供应商尚未触发的注入故障（静态规则不受影响）。 */
    @PostMapping("/faults/clear")
    public ApiResult<Void> clearFaults(@RequestBody FaultBody body) {
        faults.clear(supplier(body));
        return ApiResult.ok(null);
    }

    private static String supplier(FaultBody body) {
        if (body == null || !SUPPLIERS.contains(body.adapterKey())) {
            throw new UserFacingException("没有模拟供应商 " + (body == null ? null : body.adapterKey()) + "，可选 " + new TreeSet<>(SUPPLIERS));
        }
        return body.adapterKey();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, String what) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new UserFacingException("未知" + what + " " + name + "，可选 " + Arrays.toString(type.getEnumConstants()));
        }
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
