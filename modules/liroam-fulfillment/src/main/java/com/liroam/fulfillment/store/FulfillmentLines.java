package com.liroam.fulfillment.store;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.state.FulfillmentLineStatus;

/** 履约行仓储；幂等键 {@code uk_fulfillment_source (source_type, source_line_ref)}。 */
public class FulfillmentLines {

    public record Inserted(long id, boolean created) {
    }

    private static final String FIND = "SELECT id FROM lr_fulfillment_line WHERE source_type = ? AND source_line_ref = ?";

    private final JdbcClient jdbc;

    public FulfillmentLines(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> find(FulfillmentRequest.SourceType type, String sourceLineRef) {
        return jdbc.sql(FIND).params(type.name(), sourceLineRef).query(Long.class).optional();
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
}
