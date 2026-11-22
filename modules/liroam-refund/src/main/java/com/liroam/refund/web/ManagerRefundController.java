package com.liroam.refund.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.refund.RefundCaseView;
import com.liroam.refund.RefundCases;
import com.liroam.refund.RefundRules;
import com.liroam.refund.RefundRules.Rule;
import com.liroam.refund.RefundRules.RuleInput;

/** 后台退款：列表、审批/驳回/完成（钱已退），退款规则查看与修改（改即新版本）。 */
@RestController
@RequestMapping("/manager/liroam")
public class ManagerRefundController {

    private final CurrentActor actor;
    private final RefundCases cases;
    private final RefundRules rules;

    public ManagerRefundController(CurrentActor actor, RefundCases cases, RefundRules rules) {
        this.actor = actor;
        this.cases = cases;
        this.rules = rules;
    }

    @GetMapping("/refunds")
    public ApiResult<PageResult<RefundCaseView.Admin>> page(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNumber, @RequestParam(defaultValue = "20") int pageSize) {
        actor.requireOperator();
        return ApiResult.ok(cases.page(status, pageNumber, pageSize));
    }

    @PostMapping("/refunds/{id}/approve")
    public ApiResult<RefundCaseView> approve(@PathVariable long id) {
        return ApiResult.ok(cases.approve(id, actor.requireOperator()));
    }

    @PostMapping("/refunds/{id}/reject")
    public ApiResult<RefundCaseView> reject(@PathVariable long id) {
        return ApiResult.ok(cases.reject(id, actor.requireOperator()));
    }

    @PostMapping("/refunds/{id}/complete")
    public ApiResult<RefundCaseView> complete(@PathVariable long id) {
        return ApiResult.ok(cases.complete(id, actor.requireOperator()));
    }

    @GetMapping("/refund-rules")
    public ApiResult<List<Rule>> rules() {
        actor.requireOperator();
        return ApiResult.ok(rules.current());
    }

    @PostMapping("/refund-rules")
    public ApiResult<Rule> saveRule(@RequestBody(required = false) RuleInput body) {
        String operator = actor.requireOperator();
        if (body == null) {
            throw new UserFacingException("请填写规则");
        }
        return ApiResult.ok(rules.save(body, operator));
    }
}
