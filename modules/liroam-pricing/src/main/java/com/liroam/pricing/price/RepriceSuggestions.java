package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.PageResult;
import com.liroam.pricing.DisplayTime;

/** 调价建议查询；确认/驳回见 {@link PriceVersions}，生成见 {@link Repricing}。 */
public class RepriceSuggestions {

    private final JdbcClient jdbc;

    public RepriceSuggestions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Stored(long id, long skuBindingId, String liGoodsSkuId, String triggerKind, long currentPriceFen, long suggestedPriceFen,
            BigDecimal marginBefore, BigDecimal marginAfter, String status, LocalDateTime createdAt, String decidedBy, LocalDateTime decidedAt) {

        RepriceSuggestionRow view() {
            return new RepriceSuggestionRow(id, skuBindingId, liGoodsSkuId, triggerKind, currentPriceFen, suggestedPriceFen, marginBefore,
                    marginAfter, status, DisplayTime.format(UtcColumns.fromColumn(createdAt)), decidedBy,
                    DisplayTime.format(UtcColumns.fromColumn(decidedAt)));
        }
    }

    /** @param status 为空列全部；新的在前 */
    public PageResult<RepriceSuggestionRow> page(String status, int pageNumber, int pageSize) {
        String where = status == null || status.isBlank() ? "" : " WHERE s.status = ?";
        List<Object> params = new ArrayList<>();
        if (!where.isEmpty()) {
            params.add(status);
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM lr_reprice_suggestion s" + where).params(params).query(Long.class).single();
        int size = PageResult.clampSize(pageSize);
        params.add(size);
        params.add(PageResult.offset(pageNumber, pageSize));
        List<RepriceSuggestionRow> rows = jdbc.sql("""
                SELECT s.id, s.sku_binding_id, b.li_goods_sku_id, s.trigger_kind, s.current_price_fen, s.suggested_price_fen, s.margin_before,
                       s.margin_after, s.status, s.created_at, s.decided_by, s.decided_at
                  FROM lr_reprice_suggestion s JOIN lr_sku_binding b ON b.id = s.sku_binding_id
                """ + where + " ORDER BY s.id DESC LIMIT ? OFFSET ?").params(params).query(Stored.class).list()
                .stream().map(Stored::view).toList();
        return new PageResult<>(rows, total, Math.max(pageNumber, 1), size);
    }
}
