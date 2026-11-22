package com.liroam.fulfillment.store;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.catalog.SkuBinding.KycGateMode;
import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.state.FulfillmentLineStatus;

/** 履约行仓储；幂等键 {@code uk_fulfillment_source (source_type, source_line_ref)}。 */
public class FulfillmentLines {

    public record Inserted(long id, boolean created) {
    }

    public record Line(
            long id,
            SourceType sourceType,
            String sourceRef,
            String sourceLineRef,
            int qty,
            long skuBindingId,
            String ownerMemberId,
            Long topupCardAssetId,
            FulfillmentLineStatus status,
            int attempts) {
    }

    private static final String FIND = "SELECT id FROM lr_fulfillment_line WHERE source_type = ? AND source_line_ref = ?";
    private static final String SELECT = """
            SELECT id, source_type, source_ref, source_line_ref, qty, sku_binding_id, owner_member_id, topup_card_asset_id, status, attempts
              FROM lr_fulfillment_line
            """;

    private final JdbcClient jdbc;

    public FulfillmentLines(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> find(FulfillmentRequest.SourceType type, String sourceLineRef) {
        return jdbc.sql(FIND).params(type.name(), sourceLineRef).query(Long.class).optional();
    }

    public Optional<Line> get(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Line.class).optional();
    }

    public Optional<Line> lock(long id) {
        return jdbc.sql(SELECT + " WHERE id = ? FOR UPDATE").param(id).query(Line.class).optional();
    }

    /** 事务内认领一条待拆的行；并发 worker 跳过彼此锁住的行。 */
    public Optional<Line> claimReceived(int maxAttempts) {
        return jdbc.sql(SELECT + " WHERE status = 'RECEIVED' AND attempts < ? ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
                .param(maxAttempts).query(Line.class).optional();
    }

    /** 同一张壳订单（或渠道单）上归本站履约、需要回写壳发货的行。 */
    public List<Line> shellOrderLines(String sourceRef) {
        return jdbc.sql(SELECT + " WHERE source_ref = ? AND source_type IN ('SHOP', 'TOPUP') ORDER BY id").param(sourceRef).query(Line.class).list();
    }

    /**
     * 插入 RECEIVED 行；与并发插入撞唯一键时返回对方那行。
     * 撞键后用加锁读：同一事务的快照可能看不到对方刚提交的行。
     */
    public Inserted insertReceived(FulfillmentRequest r) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                    INSERT INTO lr_fulfillment_line (source_type, source_ref, source_line_ref, qty, sku_binding_id, owner_member_id,
                                                     channel_order_line_id, topup_card_asset_id, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(r.sourceType().name(), r.sourceRef(), r.sourceLineRef(), r.qty(), r.skuBindingId(), r.ownerMemberId(),
                            r.channelOrderLineId(), r.topupCardAssetId(), FulfillmentLineStatus.RECEIVED.name())
                    .update(keys);
            return new Inserted(keys.getKey().longValue(), true);
        } catch (DuplicateKeyException e) {
            long id = jdbc.sql(FIND + " FOR UPDATE").params(r.sourceType().name(), r.sourceLineRef()).query(Long.class).single();
            return new Inserted(id, false);
        }
    }

    /** CAS 迁移；状态机不允许时抛异常。 */
    public boolean move(long id, FulfillmentLineStatus from, FulfillmentLineStatus to) {
        FulfillmentLineStatus.MACHINE.requireMove(from, to);
        return jdbc.sql("UPDATE lr_fulfillment_line SET status = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ? AND status = ?")
                .params(to.name(), id, from.name()).update() == 1;
    }

    /** 行所属 SKU 的实名闸。 */
    public KycGateMode kycGateMode(long lineId) {
        return KycGateMode.valueOf(jdbc.sql("SELECT b.kyc_gate_mode FROM lr_fulfillment_line l JOIN lr_sku_binding b ON b.id = l.sku_binding_id WHERE l.id = ?")
                .param(lineId).query(String.class).single());
    }

    public void failedAttempt(long id, String error) {
        jdbc.sql("UPDATE lr_fulfillment_line SET attempts = attempts + 1, last_error = ?, updated_at = UTC_TIMESTAMP(3) WHERE id = ?")
                .params(Texts.clip(error, 512), id).update();
    }
}
