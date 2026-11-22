package com.liroam.fulfillment;

import com.liroam.fulfillment.store.FulfillmentLines;
import com.liroam.fulfillment.store.FulfillmentLines.Inserted;

/**
 * 唯一履约入口（domain-design §7.1）：自营支付、补偿扫描、渠道入单、加包都只调 {@link #accept}。
 * 同步完成校验与幂等落 {@code lr_fulfillment_line}(RECEIVED)，不调上游；在调用方事务内执行。
 * 拆卡、生成指令、交码由 worker 异步推进（P2）。
 */
public class FulfillmentEntry {

    private final FulfillmentRules rules;
    private final FulfillmentLines lines;

    public FulfillmentEntry(FulfillmentRules rules, FulfillmentLines lines) {
        this.rules = rules;
        this.lines = lines;
    }

    /**
     * 重复请求直接返回已有行，不再校验（SKU 事后下架不影响已收单的行）。
     *
     * @throws FulfillmentRejected 校验不通过，未落任何台账
     */
    public FulfillmentAck accept(FulfillmentRequest request) {
        var existing = lines.find(request.sourceType(), request.sourceLineRef());
        if (existing.isPresent()) {
            return new FulfillmentAck(existing.get(), false);
        }
        rules.verify(request);
        Inserted inserted = lines.insertReceived(request);
        return new FulfillmentAck(inserted.id(), inserted.created());
    }
}
