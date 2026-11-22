package com.liroam.ops.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.ops.health.OpsOverview;
import com.liroam.ops.health.OpsOverview.Overview;
import com.liroam.ops.keys.DataKeyRotation;
import com.liroam.ops.keys.DataKeyRotation.ResealResult;
import com.liroam.ops.keys.DataKeyRotation.Status;

/** 后台「运维概览」：容量与积压、数据密钥版本分布与分批重加密。 */
@RestController
@RequestMapping("/manager/liroam/ops")
public class ManagerOpsHealthController {

    public record ResealBody(Integer batchSize) {
    }

    private final CurrentActor actor;
    private final OpsOverview overview;
    private final DataKeyRotation keys;

    public ManagerOpsHealthController(CurrentActor actor, OpsOverview overview, DataKeyRotation keys) {
        this.actor = actor;
        this.overview = overview;
        this.keys = keys;
    }

    @GetMapping("/overview")
    public ApiResult<Overview> overview() {
        actor.requireOperator();
        return ApiResult.ok(overview.overview());
    }

    @GetMapping("/data-keys")
    public ApiResult<Status> dataKeys() {
        actor.requireOperator();
        return ApiResult.ok(keys.status());
    }

    @PostMapping("/data-keys/reseal")
    public ApiResult<ResealResult> reseal(@RequestBody(required = false) ResealBody body) {
        String operator = actor.requireOperator();
        return ApiResult.ok(keys.reseal(body == null || body.batchSize() == null ? 200 : body.batchSize(), operator));
    }
}
