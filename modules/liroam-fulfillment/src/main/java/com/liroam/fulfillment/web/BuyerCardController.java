package com.liroam.fulfillment.web;

import java.util.List;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.FulfillmentRejected;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardQueries.CredentialView;
import com.liroam.fulfillment.card.CardQueries.TopUpOption;
import com.liroam.fulfillment.card.CardView;
import com.liroam.fulfillment.poll.CardRefresh;

/** 买家「我的卡」：列表、详情（顺带按需刷新用量）、查看激活码、加包选项与选卡。 */
@RestController
@RequestMapping("/buyer/liroam/cards")
public class BuyerCardController {

    public record TopUpIntentRequest(String skuId) {
    }

    private final CurrentActor actor;
    private final CardQueries queries;
    private final CardRefresh refresh;
    private final TopUpIntents intents;

    public BuyerCardController(CurrentActor actor, CardQueries queries, CardRefresh refresh, TopUpIntents intents) {
        this.actor = actor;
        this.queries = queries;
        this.refresh = refresh;
        this.intents = intents;
    }

    @GetMapping
    public ApiResult<List<CardView>> list() {
        return ApiResult.ok(queries.listForMember(actor.requireMemberId()));
    }

    /** 返回当前台账；冷却外顺带排一次用量查询，前端稍后再取即可看到新用量。 */
    @GetMapping("/{id}")
    public ApiResult<CardView> get(@PathVariable long id) {
        CardView card = queries.getForMember(id, actor.requireMemberId()).orElseThrow(() -> new UserFacingException("找不到这张卡"));
        refresh.requestOnDemand(id);
        return ApiResult.ok(card);
    }

    /** {@code result = null}：还没有可看的激活码。 */
    @GetMapping("/{id}/credential")
    public ApiResult<CredentialView> credential(@PathVariable long id) {
        return ApiResult.ok(queries.credentialForMember(id, actor.requireMemberId()).orElse(null));
    }

    @GetMapping("/{id}/topup-options")
    public ApiResult<List<TopUpOption>> topUpOptions(@PathVariable long id) {
        return ApiResult.ok(queries.topUpOptions(id, actor.requireMemberId()));
    }

    @PostMapping("/{id}/topup-intents")
    public ApiResult<TopUpIntents.Opened> openTopUpIntent(@PathVariable long id, @RequestBody TopUpIntentRequest body) {
        if (body == null || body.skuId() == null || body.skuId().isBlank()) {
            throw new UserFacingException("请选择加包");
        }
        return ApiResult.ok(intents.open(actor.requireMemberId(), id, body.skuId().trim()));
    }

    @ExceptionHandler(FulfillmentRejected.class)
    public ApiResult<Void> rejected(FulfillmentRejected e) {
        return ApiResult.fail(e.reason().userMessage());
    }
}
