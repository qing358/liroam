package com.liroam.fulfillment.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.state.CardPlanStatus;
import com.liroam.supplier.spi.UsageSnapshot.PlanUsage;

/** 卡套餐仓储（首购 BASE / 加包 TOP_UP）。 */
public class CardPlans {

    public enum Kind { BASE, TOP_UP }

    public record Plan(
            long id,
            long cardAssetId,
            long fulfillmentLineId,
            Kind kind,
            long planTemplateId,
            long offeringId,
            CardPlanStatus status,
            String upstreamPlanRef,
            Long quotaBytes,
            Long usedBytes,
            Long remainingBytes,
            Long voiceRemainingSeconds,
            Integer smsRemaining,
            LocalDateTime startsAt,
            LocalDateTime expiresAt,
            LocalDateTime mirroredAt) {
    }

    private static final String SELECT = """
            SELECT id, card_asset_id, fulfillment_line_id, kind, plan_template_id, offering_id, status, upstream_plan_ref,
                   quota_bytes, used_bytes, remaining_bytes, voice_remaining_seconds, sms_remaining, starts_at, expires_at, mirrored_at
              FROM lr_card_plan
            """;

    private final JdbcClient jdbc;

    public CardPlans(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long cardAssetId, long fulfillmentLineId, Kind kind, long planTemplateId, long offeringId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_card_plan (card_asset_id, fulfillment_line_id, kind, plan_template_id, offering_id, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(cardAssetId, fulfillmentLineId, kind.name(), planTemplateId, offeringId, CardPlanStatus.PENDING.name()).update(keys);
        return keys.getKey().longValue();
    }

    public Optional<Plan> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Plan.class).optional();
    }

    public List<Plan> byCard(long cardAssetId) {
        return jdbc.sql(SELECT + " WHERE card_asset_id = ? ORDER BY id").param(cardAssetId).query(Plan.class).list();
    }

    public List<Plan> byLine(long fulfillmentLineId) {
        return jdbc.sql(SELECT + " WHERE fulfillment_line_id = ? ORDER BY id").param(fulfillmentLineId).query(Plan.class).list();
    }

    /** CAS 迁移；状态机不允许时抛异常。 */
    public boolean move(Plan plan, CardPlanStatus to) {
        CardPlanStatus.MACHINE.requireMove(plan.status(), to);
        return jdbc.sql("UPDATE lr_card_plan SET status = ?, version = version + 1 WHERE id = ? AND status = ?")
                .params(to.name(), plan.id(), plan.status().name()).update() == 1;
    }

    /** 沿 PENDING → PROVISIONING → READY 走到 READY；已在 PROVISIONING 则一步。 */
    public void ready(Plan plan, String upstreamPlanRef) {
        Plan current = plan;
        if (current.status() == CardPlanStatus.PENDING) {
            move(current, CardPlanStatus.PROVISIONING);
            current = find(plan.id()).orElseThrow();
        }
        if (current.status() == CardPlanStatus.PROVISIONING) {
            move(current, CardPlanStatus.READY);
        }
        if (upstreamPlanRef != null) {
            jdbc.sql("UPDATE lr_card_plan SET upstream_plan_ref = ? WHERE id = ?").params(upstreamPlanRef, plan.id()).update();
        }
    }

    public void mirror(long planId, PlanUsage usage, String rawJson, Instant now) {
        jdbc.sql("""
                UPDATE lr_card_plan
                   SET upstream_plan_ref = COALESCE(upstream_plan_ref, ?), quota_bytes = ?, used_bytes = ?, remaining_bytes = ?,
                       voice_quota_seconds = ?, voice_used_seconds = ?, voice_remaining_seconds = ?, sms_quota = ?, sms_used = ?, sms_remaining = ?,
                       starts_at = ?, expires_at = ?, starts_at_raw = ?, expires_at_raw = ?,
                       raw_json = IF(JSON_VALID(?), CAST(? AS JSON), JSON_QUOTE(?)), mirrored_at = ?
                 WHERE id = ?
                """).params(usage.upstreamPlanRef(), usage.quotaBytes(), usage.usedBytes(), usage.remainingBytes(),
                usage.voiceQuotaSeconds(), usage.voiceUsedSeconds(), usage.voiceRemainingSeconds(), usage.smsQuota(), usage.smsUsed(),
                usage.smsRemaining(), UtcColumns.toColumn(usage.startsAt()), UtcColumns.toColumn(usage.expiresAt()),
                usage.startsAtRaw(), usage.expiresAtRaw(), rawJson, rawJson, rawJson, UtcColumns.toColumn(now), planId).update();
    }

    /** 套餐流量不限量：模板未设流量额度且卡产品有流量能力（无流量能力的纯语音套餐不算）。 */
    public boolean unlimitedData(long planId) {
        return jdbc.sql("""
                SELECT t.data_quota_bytes IS NULL AND p.cap_data = 1
                  FROM lr_card_plan cp
                  JOIN lr_plan_template t ON t.id = cp.plan_template_id
                  JOIN lr_card_asset a ON a.id = cp.card_asset_id
                  JOIN lr_card_product p ON p.id = a.card_product_id
                 WHERE cp.id = ?
                """).param(planId).query(Boolean.class).single();
    }
}
