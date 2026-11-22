package com.liroam.channel.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.channel.ChannelClaims;
import com.liroam.channel.ChannelClaims.ClaimResult;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;

/** 买家认领渠道订单：渠道单号 + 收货手机号，认领后整单的卡进「我的卡」。 */
@RestController
@RequestMapping("/buyer/liroam/channel-orders")
public class BuyerChannelOrderController {

    public record ClaimBody(String orderNo, String phone) {
    }

    private final CurrentActor actor;
    private final ChannelClaims claims;

    public BuyerChannelOrderController(CurrentActor actor, ChannelClaims claims) {
        this.actor = actor;
        this.claims = claims;
    }

    @PostMapping("/claim")
    public ApiResult<ClaimResult> claim(@RequestBody(required = false) ClaimBody body) {
        String memberId = actor.requireMemberId();
        ClaimBody b = body == null ? new ClaimBody(null, null) : body;
        return ApiResult.ok(claims.claim(memberId, b.orderNo(), b.phone()));
    }
}
