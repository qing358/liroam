package com.liroam.channel;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.pricing.Json;

/** 渠道 SKU ↔ 本站旅行卡新卡 SKU（{@code lr_channel_sku_mapping}）。停用的映射等同未映射；增删改留审计。 */
public class ChannelSkuMappings {

    public record MappingView(long id, long channelId, String externalSkuId, String liGoodsSkuId, boolean enabled, String updatedText) {
    }

    private record Row(long id, long channelId, String externalSkuId, String liGoodsSkuId, boolean enabled, LocalDateTime updatedAt) {
    }

    private static final String SELECT = "SELECT id, channel_id, external_sku_id, li_goods_sku_id, enabled, updated_at FROM lr_channel_sku_mapping";

    private final JdbcClient jdbc;
    private final ChannelDirectory channels;
    private final SkuBindings bindings;
    private final AuditLog audit;

    public ChannelSkuMappings(JdbcClient jdbc, ChannelDirectory channels, SkuBindings bindings, AuditLog audit) {
        this.jdbc = jdbc;
        this.channels = channels;
        this.bindings = bindings;
        this.audit = audit;
    }

    /** 渠道 SKU 当前映射到的本站 SKU；没有或已停用为空。 */
    Optional<String> mapped(long channelId, String externalSkuId) {
        return jdbc.sql("SELECT li_goods_sku_id FROM lr_channel_sku_mapping WHERE channel_id = ? AND external_sku_id = ? AND enabled = 1")
                .params(channelId, externalSkuId).query(String.class).optional();
    }

    public List<MappingView> list(long channelId) {
        return jdbc.sql(SELECT + " WHERE channel_id = ? ORDER BY external_sku_id").param(channelId).query(Row.class).list().stream()
                .map(ChannelSkuMappings::view).toList();
    }

    public MappingView create(long channelId, String externalSkuId, String liGoodsSkuId, String actor) {
        channels.require(channelId);
        String external = required(externalSkuId);
        String sku = requireNewCardSku(liGoodsSkuId);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO lr_channel_sku_mapping (channel_id, external_sku_id, li_goods_sku_id) VALUES (?, ?, ?)")
                    .params(channelId, external, sku).update(keys);
        } catch (DuplicateKeyException e) {
            throw new UserFacingException("渠道 SKU " + external + " 已有映射");
        }
        Row created = require(keys.getKey().longValue());
        audit.record(actor, "CHANNEL_SKU_MAPPING_CREATE", "CHANNEL_SKU_MAPPING", Long.toString(created.id()), null, Json.write(audited(created)));
        return view(created);
    }

    public MappingView update(long id, String liGoodsSkuId, Boolean enabled, String actor) {
        Row before = require(id);
        String sku = liGoodsSkuId == null ? before.liGoodsSkuId() : requireNewCardSku(liGoodsSkuId);
        boolean on = enabled == null ? before.enabled() : enabled;
        jdbc.sql("UPDATE lr_channel_sku_mapping SET li_goods_sku_id = ?, enabled = ?, updated_at = UTC_TIMESTAMP(3) WHERE id = ?")
                .params(sku, on, id).update();
        Row after = require(id);
        audit.record(actor, "CHANNEL_SKU_MAPPING_UPDATE", "CHANNEL_SKU_MAPPING", Long.toString(id), Json.write(audited(before)),
                Json.write(audited(after)));
        return view(after);
    }

    public void delete(long id, String actor) {
        Row before = require(id);
        jdbc.sql("DELETE FROM lr_channel_sku_mapping WHERE id = ?").param(id).update();
        audit.record(actor, "CHANNEL_SKU_MAPPING_DELETE", "CHANNEL_SKU_MAPPING", Long.toString(id), Json.write(audited(before)), null);
    }

    private Row require(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Row.class).optional().orElseThrow(() -> new UserFacingException("找不到这条映射"));
    }

    private String requireNewCardSku(String liGoodsSkuId) {
        String sku = required(liGoodsSkuId);
        boolean newCard = bindings.findByShellSkuId(sku).map(b -> b.skuKind() == SkuBinding.SkuKind.NEW_CARD).orElse(false);
        if (!newCard) {
            throw new UserFacingException("本站 SKU " + sku + " 不是旅行卡新卡商品");
        }
        return sku;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new UserFacingException("请填写渠道 SKU 与本站 SKU");
        }
        return value.trim();
    }

    private static Map<String, Object> audited(Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channelId", r.channelId());
        m.put("externalSkuId", r.externalSkuId());
        m.put("liGoodsSkuId", r.liGoodsSkuId());
        m.put("enabled", r.enabled());
        return m;
    }

    private static MappingView view(Row r) {
        return new MappingView(r.id(), r.channelId(), r.externalSkuId(), r.liGoodsSkuId(), r.enabled(), DisplayTime.format(UtcColumns.fromColumn(r.updatedAt())));
    }
}
