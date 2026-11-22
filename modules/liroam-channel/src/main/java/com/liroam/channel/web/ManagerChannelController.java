package com.liroam.channel.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.channel.ChannelDirectory;
import com.liroam.channel.ChannelDirectory.ChannelConfig;
import com.liroam.channel.ChannelDirectory.ChannelView;
import com.liroam.channel.ChannelOrderView;
import com.liroam.channel.ChannelOrders;
import com.liroam.channel.ChannelOrders.OrderFilter;
import com.liroam.channel.ChannelOrders.PullResult;
import com.liroam.channel.ChannelReconciliation;
import com.liroam.channel.ChannelReconciliation.DiffView;
import com.liroam.channel.ChannelReconciliation.ReconSummary;
import com.liroam.channel.ChannelSkuMappings;
import com.liroam.channel.ChannelSkuMappings.MappingView;
import com.liroam.channel.ChannelWriteBacks;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;

/** 后台渠道：配置（启停、拉单间隔、计佣、买家看用量）、SKU 映射、渠道订单（回传 / 对账标记、重放、重试回传）、立即拉单、跑日对账。 */
@RestController
@RequestMapping("/manager/liroam/channels")
public class ManagerChannelController {

    public record MappingBody(String externalSkuId, String liGoodsSkuId, Boolean enabled) {
    }

    private final CurrentActor actor;
    private final ChannelDirectory channels;
    private final ChannelSkuMappings mappings;
    private final ChannelOrders orders;
    private final ChannelWriteBacks writeBacks;
    private final ChannelReconciliation reconciliation;

    public ManagerChannelController(CurrentActor actor, ChannelDirectory channels, ChannelSkuMappings mappings, ChannelOrders orders,
            ChannelWriteBacks writeBacks, ChannelReconciliation reconciliation) {
        this.actor = actor;
        this.channels = channels;
        this.mappings = mappings;
        this.orders = orders;
        this.writeBacks = writeBacks;
        this.reconciliation = reconciliation;
    }

    @GetMapping
    public ApiResult<List<ChannelView>> list() {
        actor.requireOperator();
        return ApiResult.ok(channels.list());
    }

    @PutMapping("/{id}")
    public ApiResult<ChannelView> update(@PathVariable long id, @RequestBody(required = false) ChannelConfig body) {
        String operator = actor.requireOperator();
        if (body == null) {
            throw new UserFacingException("请填写渠道配置");
        }
        return ApiResult.ok(channels.update(id, body, operator));
    }

    @GetMapping("/{id}/sku-mappings")
    public ApiResult<List<MappingView>> mappings(@PathVariable long id) {
        actor.requireOperator();
        return ApiResult.ok(mappings.list(id));
    }

    @PostMapping("/{id}/sku-mappings")
    public ApiResult<MappingView> createMapping(@PathVariable long id, @RequestBody(required = false) MappingBody body) {
        String operator = actor.requireOperator();
        MappingBody b = body == null ? new MappingBody(null, null, null) : body;
        return ApiResult.ok(mappings.create(id, b.externalSkuId(), b.liGoodsSkuId(), operator));
    }

    @PutMapping("/sku-mappings/{mappingId}")
    public ApiResult<MappingView> updateMapping(@PathVariable long mappingId, @RequestBody(required = false) MappingBody body) {
        String operator = actor.requireOperator();
        MappingBody b = body == null ? new MappingBody(null, null, null) : body;
        return ApiResult.ok(mappings.update(mappingId, b.liGoodsSkuId(), b.enabled(), operator));
    }

    @DeleteMapping("/sku-mappings/{mappingId}")
    public ApiResult<Void> deleteMapping(@PathVariable long mappingId) {
        mappings.delete(mappingId, actor.requireOperator());
        return ApiResult.ok(null);
    }

    @GetMapping("/orders")
    public ApiResult<PageResult<ChannelOrderView>> orders(@RequestParam(required = false) Long channelId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNumber, @RequestParam(defaultValue = "20") int pageSize) {
        actor.requireOperator();
        return ApiResult.ok(orders.page(new OrderFilter(channelId, status, keyword, pageNumber, pageSize)));
    }

    @PostMapping("/{id}/pull")
    public ApiResult<PullResult> pull(@PathVariable long id) {
        return ApiResult.ok(orders.pullNow(id, actor.requireOperator()));
    }

    @PostMapping("/orders/{orderId}/replay")
    public ApiResult<ChannelOrderView> replay(@PathVariable long orderId) {
        return ApiResult.ok(orders.replay(orderId, actor.requireOperator()));
    }

    @PostMapping("/order-lines/{lineId}/writeback-retry")
    public ApiResult<Void> retryWriteBack(@PathVariable long lineId) {
        writeBacks.retry(lineId, actor.requireOperator());
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/recon")
    public ApiResult<ReconSummary> reconcile(@PathVariable long id, @RequestParam String day) {
        String operator = actor.requireOperator();
        LocalDate date;
        try {
            date = LocalDate.parse(day.trim());
        } catch (DateTimeParseException e) {
            throw new UserFacingException("对账日期格式应为 yyyy-MM-dd");
        }
        return ApiResult.ok(reconciliation.run(id, date, operator));
    }

    @GetMapping("/recon-runs/{runId}/diffs")
    public ApiResult<List<DiffView>> diffs(@PathVariable long runId) {
        actor.requireOperator();
        return ApiResult.ok(reconciliation.diffs(runId));
    }
}
