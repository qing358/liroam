package com.liroam.common.audit;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 配置、调价确认、上架、人工处理留痕（{@code lr_audit_log}）。before/after 为 JSON 文本，可空。 */
public class AuditLog {

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String targetType, String targetId, String beforeJson, String afterJson) {
        jdbc.sql("""
                INSERT INTO lr_audit_log (actor, action, target_type, target_id, before_json, after_json)
                VALUES (?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON))
                """).params(actor, action, targetType, targetId, beforeJson, afterJson).update();
    }
}
