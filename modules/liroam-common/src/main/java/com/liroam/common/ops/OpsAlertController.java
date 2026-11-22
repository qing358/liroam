package com.liroam.common.ops;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;

/** 后台「运维告警」：当前命中的告警，严重在前。 */
@RestController
@RequestMapping("/manager/liroam/ops")
public class OpsAlertController {

    private final CurrentActor actor;
    private final OpsAlerts alerts;

    public OpsAlertController(CurrentActor actor, OpsAlerts alerts) {
        this.actor = actor;
        this.alerts = alerts;
    }

    @GetMapping("/alerts")
    public ApiResult<List<OpsAlert>> alerts() {
        actor.requireOperator();
        return ApiResult.ok(alerts.current());
    }
}
