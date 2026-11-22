package com.liroam.fulfillment.delivery;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 壳发货回写读的「这张单的卡与当前物流」：实体卡取当前 SHIPMENT 交码，eSIM/软卡物流为空。 */
public class CardShipments {

    /** @param sourceLineRef 壳订单行号（{@code li_order_item.sn}） */
    public record OrderCard(long cardAssetId, String sourceLineRef, String form, String logisticsRef, String logisticsCompany, String logisticsNo) {
    }

    private final JdbcClient jdbc;

    public CardShipments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrderCard> forOrder(String liOrderSn) {
        return jdbc.sql("""
                SELECT c.id AS card_asset_id, l.source_line_ref, c.form, k.logistics_ref, k.logistics_company, k.logistics_no
                  FROM lr_fulfillment_line l
                  JOIN lr_card_asset c ON c.fulfillment_line_id = l.id
                  LEFT JOIN lr_card_credential k ON k.card_asset_id = c.id AND k.kind = 'SHIPMENT' AND k.superseded_by IS NULL
                 WHERE l.source_type = 'SHOP' AND l.source_ref = ?
                 ORDER BY l.id, c.unit_seq
                """).param(liOrderSn).query(OrderCard.class).list();
    }
}
