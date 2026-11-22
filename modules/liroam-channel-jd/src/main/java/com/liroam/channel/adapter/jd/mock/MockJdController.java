package com.liroam.channel.adapter.jd.mock;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.channel.adapter.jd.mock.MockJdChannelAdapter.MockOrderRequest;
import com.liroam.channel.adapter.jd.mock.MockJdChannelAdapter.MockOrderView;
import com.liroam.common.safety.Simulated;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.UserFacingException;

/** dev/test 才有：在模拟京东造单、改结算单（金额不符 / 漏单 / 多单）、让回传失败，再到渠道订单页拉单、对账演示。 */
@Simulated
@RestController
@RequestMapping("/manager/liroam/dev/channel-mock")
public class MockJdController {

    public record StatementBody(String orderId, String paidYuan, String day) {
    }

    public record FaultBody(int count, boolean rejected) {
    }

    private final MockJdChannelAdapter jd;

    public MockJdController(MockJdChannelAdapter jd) {
        this.jd = jd;
    }

    @GetMapping("/orders")
    public ApiResult<List<MockOrderView>> orders() {
        return ApiResult.ok(jd.orders());
    }

    @PostMapping("/orders")
    public ApiResult<MockOrderView> create(@RequestBody MockOrderRequest body) {
        return ApiResult.ok(jd.createOrder(body));
    }

    @PostMapping("/statement/adjust")
    public ApiResult<List<MockOrderView>> adjust(@RequestBody StatementBody body) {
        jd.adjustStatement(body.orderId(), body.paidYuan());
        return ApiResult.ok(jd.orders());
    }

    @PostMapping("/statement/drop")
    public ApiResult<List<MockOrderView>> drop(@RequestBody StatementBody body) {
        jd.dropFromStatement(body.orderId());
        return ApiResult.ok(jd.orders());
    }

    @PostMapping("/statement/extra")
    public ApiResult<List<MockOrderView>> extra(@RequestBody StatementBody body) {
        LocalDate day;
        try {
            day = LocalDate.parse(body.day() == null ? "" : body.day().trim());
        } catch (DateTimeParseException e) {
            throw new UserFacingException("日期格式应为 yyyy-MM-dd");
        }
        jd.addStatementOnly(day, body.orderId(), body.paidYuan());
        return ApiResult.ok(jd.orders());
    }

    @PostMapping("/writeback-faults")
    public ApiResult<FaultBody> faults(@RequestBody FaultBody body) {
        if (body.count() <= 0 || body.count() > 20) {
            throw new UserFacingException("失败次数应在 1～20");
        }
        jd.failNextWriteBacks(body.count(), body.rejected());
        return ApiResult.ok(body);
    }

    @PostMapping("/reset")
    public ApiResult<List<MockOrderView>> reset() {
        jd.reset();
        return ApiResult.ok(jd.orders());
    }
}
