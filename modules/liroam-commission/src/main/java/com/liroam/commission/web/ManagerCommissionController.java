package com.liroam.commission.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.commission.CommissionLedger;
import com.liroam.commission.CommissionLedger.AgentBalance;
import com.liroam.commission.CommissionLedger.EntryView;
import com.liroam.commission.CommissionPolicies;
import com.liroam.commission.CommissionPolicies.FloorPrice;
import com.liroam.commission.CommissionPolicies.FloorPriceInput;
import com.liroam.commission.CommissionPolicies.ModeOption;
import com.liroam.commission.CommissionPolicies.PolicyInput;
import com.liroam.commission.CommissionPolicy;
import com.liroam.commission.Withdrawals;
import com.liroam.commission.Withdrawals.Payee;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;

/** 后台佣金：策略与底价（改即新版本）、账本明细、代理余额、提现审批与人工打款登记。 */
@RestController
@RequestMapping("/manager/liroam/commission")
public class ManagerCommissionController {

    public record PolicyRow(CommissionPolicy policy, List<FloorPrice> floorPrices) {
    }

    public record NoteRequest(String note) {
    }

    public record PaidRequest(String voucher, String note) {
    }

    private final CurrentActor actor;
    private final CommissionPolicies policies;
    private final CommissionLedger ledger;
    private final Withdrawals withdrawals;

    public ManagerCommissionController(CurrentActor actor, CommissionPolicies policies, CommissionLedger ledger, Withdrawals withdrawals) {
        this.actor = actor;
        this.policies = policies;
        this.ledger = ledger;
        this.withdrawals = withdrawals;
    }

    @GetMapping("/modes")
    public ApiResult<List<ModeOption>> modes() {
        actor.requireOperator();
        return ApiResult.ok(policies.modeOptions());
    }

    @GetMapping("/policies")
    public ApiResult<List<PolicyRow>> policies() {
        actor.requireOperator();
        return ApiResult.ok(policies.current().stream().map(p -> new PolicyRow(p, policies.floorPrices(p.id()))).toList());
    }

    @PostMapping("/policies")
    public ApiResult<CommissionPolicy> save(@RequestBody(required = false) PolicyInput body) {
        String operator = actor.requireOperator();
        if (body == null) {
            throw new UserFacingException("请填写策略");
        }
        return ApiResult.ok(policies.save(body, operator));
    }

    @PostMapping("/policies/{code}/floor-prices")
    public ApiResult<CommissionPolicy> saveFloorPrice(@PathVariable String code, @RequestBody(required = false) FloorPriceInput body) {
        String operator = actor.requireOperator();
        if (body == null) {
            throw new UserFacingException("请填写底价");
        }
        return ApiResult.ok(policies.saveFloorPrice(code, body, operator));
    }

    @GetMapping("/entries")
    public ApiResult<PageResult<EntryView>> entries(@RequestParam(required = false) String agentRef, @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        actor.requireOperator();
        return ApiResult.ok(ledger.entries(agentRef, pageNumber, pageSize));
    }

    @GetMapping("/balances")
    public ApiResult<PageResult<AgentBalance>> balances(@RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        actor.requireOperator();
        return ApiResult.ok(ledger.balances(pageNumber, pageSize));
    }

    @GetMapping("/withdrawals")
    public ApiResult<PageResult<Withdrawals.View>> withdrawals(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNumber, @RequestParam(defaultValue = "20") int pageSize) {
        actor.requireOperator();
        return ApiResult.ok(withdrawals.page(status, pageNumber, pageSize));
    }

    @GetMapping("/withdrawals/{id}/payee")
    public ApiResult<Payee> payee(@PathVariable long id) {
        return ApiResult.ok(withdrawals.payee(id, actor.requireOperator()));
    }

    @PostMapping("/withdrawals/{id}/approve")
    public ApiResult<Withdrawals.View> approve(@PathVariable long id) {
        return ApiResult.ok(withdrawals.approve(id, actor.requireOperator()));
    }

    @PostMapping("/withdrawals/{id}/reject")
    public ApiResult<Withdrawals.View> reject(@PathVariable long id, @RequestBody(required = false) NoteRequest body) {
        return ApiResult.ok(withdrawals.reject(id, actor.requireOperator(), body == null ? null : body.note()));
    }

    @PostMapping("/withdrawals/{id}/paid")
    public ApiResult<Withdrawals.View> paid(@PathVariable long id, @RequestBody(required = false) PaidRequest body) {
        return ApiResult.ok(withdrawals.markPaid(id, actor.requireOperator(), body == null ? null : body.voucher(), body == null ? null : body.note()));
    }
}
