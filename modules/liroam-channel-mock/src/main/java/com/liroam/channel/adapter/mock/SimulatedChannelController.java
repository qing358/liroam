package com.liroam.channel.adapter.mock;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.channel.adapter.mock.SimulatedChannel.OrderRequest;
import com.liroam.channel.adapter.mock.SimulatedChannel.OrderView;
import com.liroam.common.safety.Simulated;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.UserFacingException;

/**
 * dev/test 才有：按 {@code adapter_key} 操作任一模拟渠道——造单、改结算单（金额不符 / 漏单 / 多单）、让回传失败，
 * 再到「渠道订单」页拉单、对账演示。新渠道的模拟实现只要是 {@link SimulatedChannel} Bean，这里与管理端页面都不用改。
 */
@Simulated
@RestController
@RequestMapping("/manager/liroam/dev/channel-mock")
public class SimulatedChannelController {

    public record SimulatedChannelView(String adapterKey, String name) {
    }

    public record StatementBody(String orderId, String paidYuan, String day) {
    }

    public record FaultBody(int count, boolean rejected) {
    }

    private final CurrentActor actor;
    private final ListableBeanFactory beans;

    public SimulatedChannelController(CurrentActor actor, ListableBeanFactory beans) {
        this.actor = actor;
        this.beans = beans;
    }

    @GetMapping
    public ApiResult<List<SimulatedChannelView>> channels() {
        actor.requireOperator();
        return ApiResult.ok(simulated().entrySet().stream()
                .map(e -> new SimulatedChannelView(e.getKey(), e.getValue().displayName())).toList());
    }

    @GetMapping("/{adapterKey}/orders")
    public ApiResult<List<OrderView>> orders(@PathVariable String adapterKey) {
        return ApiResult.ok(require(adapterKey).orders());
    }

    @PostMapping("/{adapterKey}/orders")
    public ApiResult<OrderView> create(@PathVariable String adapterKey, @RequestBody OrderRequest body) {
        return ApiResult.ok(require(adapterKey).createOrder(body));
    }

    @PostMapping("/{adapterKey}/statement/adjust")
    public ApiResult<List<OrderView>> adjust(@PathVariable String adapterKey, @RequestBody StatementBody body) {
        SimulatedChannel channel = require(adapterKey);
        channel.adjustStatement(body.orderId(), body.paidYuan());
        return ApiResult.ok(channel.orders());
    }

    @PostMapping("/{adapterKey}/statement/drop")
    public ApiResult<List<OrderView>> drop(@PathVariable String adapterKey, @RequestBody StatementBody body) {
        SimulatedChannel channel = require(adapterKey);
        channel.dropFromStatement(body.orderId());
        return ApiResult.ok(channel.orders());
    }

    @PostMapping("/{adapterKey}/statement/extra")
    public ApiResult<List<OrderView>> extra(@PathVariable String adapterKey, @RequestBody StatementBody body) {
        SimulatedChannel channel = require(adapterKey);
        LocalDate day;
        try {
            day = LocalDate.parse(body.day() == null ? "" : body.day().trim());
        } catch (DateTimeParseException e) {
            throw new UserFacingException("日期格式应为 yyyy-MM-dd");
        }
        channel.addStatementOnly(day, body.orderId(), body.paidYuan());
        return ApiResult.ok(channel.orders());
    }

    @PostMapping("/{adapterKey}/writeback-faults")
    public ApiResult<FaultBody> faults(@PathVariable String adapterKey, @RequestBody FaultBody body) {
        SimulatedChannel channel = require(adapterKey);
        if (body.count() <= 0 || body.count() > 20) {
            throw new UserFacingException("失败次数应在 1～20");
        }
        channel.failNextWriteBacks(body.count(), body.rejected());
        return ApiResult.ok(body);
    }

    @PostMapping("/{adapterKey}/reset")
    public ApiResult<List<OrderView>> reset(@PathVariable String adapterKey) {
        SimulatedChannel channel = require(adapterKey);
        channel.reset();
        return ApiResult.ok(channel.orders());
    }

    private Map<String, SimulatedChannel> simulated() {
        return beans.getBeansOfType(SimulatedChannel.class);
    }

    private SimulatedChannel require(String adapterKey) {
        actor.requireOperator();
        SimulatedChannel channel = simulated().get(adapterKey);
        if (channel == null) {
            throw new UserFacingException("没有模拟渠道 " + adapterKey);
        }
        return channel;
    }
}
