package com.liroam.commission.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.commission.AgentDirectory;
import com.liroam.commission.CommissionLedger;
import com.liroam.commission.CommissionLedger.EntryView;
import com.liroam.commission.Withdrawals;
import com.liroam.commission.Withdrawals.Payee;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;

/** 代理端「我的佣金」：余额、明细、提现申请与记录。代理 = 壳里审核通过的分销员。 */
@RestController
@RequestMapping("/buyer/liroam/agent")
public class BuyerAgentController {

    public record WithdrawRequest(Long amountFen, String method, String name, String account) {
    }

    private final CurrentActor actor;
    private final AgentDirectory agents;
    private final CommissionLedger ledger;
    private final Withdrawals withdrawals;

    public BuyerAgentController(CurrentActor actor, AgentDirectory agents, CommissionLedger ledger, Withdrawals withdrawals) {
        this.actor = actor;
        this.agents = agents;
        this.ledger = ledger;
        this.withdrawals = withdrawals;
    }

    @GetMapping("/summary")
    public ApiResult<Withdrawals.Summary> summary() {
        return ApiResult.ok(withdrawals.summary(actor.requireMemberId()));
    }

    @GetMapping("/entries")
    public ApiResult<PageResult<EntryView>> entries(@RequestParam(defaultValue = "1") int pageNumber, @RequestParam(defaultValue = "20") int pageSize) {
        String agentRef = agents.agentOfMember(actor.requireMemberId()).orElseThrow(() -> new UserFacingException("你还不是分销员")).agentRef();
        return ApiResult.ok(ledger.entries(agentRef, pageNumber, pageSize));
    }

    @GetMapping("/withdrawals")
    public ApiResult<List<Withdrawals.View>> withdrawals() {
        return ApiResult.ok(withdrawals.mine(actor.requireMemberId()));
    }

    @PostMapping("/withdrawals")
    public ApiResult<Withdrawals.View> apply(@RequestBody(required = false) WithdrawRequest body) {
        String memberId = actor.requireMemberId();
        if (body == null || body.amountFen() == null) {
            throw new UserFacingException("请填写提现金额");
        }
        return ApiResult.ok(withdrawals.apply(memberId, body.amountFen(), new Payee(body.method(), body.name(), body.account())));
    }
}
