package com.liroam.supplier.spi;

import java.util.Map;

/** 回调入口：验签失败返回 {@link Outcome.Rejected}；成功产出与轮询同结构的 {@link UsageSnapshot}。 */
public interface WebhookParser {

    Outcome<UsageSnapshot> parse(Map<String, String> headers, byte[] body);
}
