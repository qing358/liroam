package com.liroam.fulfillment.store;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 上游调用日志（{@code lr_supplier_call_log}）；请求与响应只写脱敏摘要，不写 ICCID 全号与交码内容。 */
public class CallLogs {

    private final JdbcClient jdbc;

    public CallLogs(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Long commandId, long supplierId, String operation, long latencyMs, String requestRedacted, String responseRedacted, String outcome) {
        jdbc.sql("""
                INSERT INTO lr_supplier_call_log (command_id, supplier_id, operation, latency_ms, request_redacted, response_redacted, outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(commandId, supplierId, operation, (int) Math.min(latencyMs, Integer.MAX_VALUE), requestRedacted,
                Texts.clip(responseRedacted, 2000), outcome).update();
    }
}
