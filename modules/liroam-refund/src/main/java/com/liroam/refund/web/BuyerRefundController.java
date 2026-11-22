package com.liroam.refund.web;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.refund.RefundCaseView;
import com.liroam.refund.RefundCases;

/** 买家在「我的卡」申请退款：整卡，或带 {@code cardPlanId} 退卡上的一个加包。 */
@RestController
public class BuyerRefundController {

    public record RefundRequest(String reason, Long cardPlanId) {
    }

    private final CurrentActor actor;
    private final RefundCases cases;

    public BuyerRefundController(CurrentActor actor, RefundCases cases) {
        this.actor = actor;
        this.cases = cases;
    }

    @PostMapping("/buyer/liroam/cards/{id}/refund")
    public ApiResult<RefundCaseView> apply(@PathVariable long id, @RequestBody(required = false) RefundRequest body) {
        String memberId = actor.requireMemberId();
        return ApiResult.ok(cases.apply(id, body == null ? null : body.cardPlanId(), memberId, body == null ? null : body.reason()));
    }
}
