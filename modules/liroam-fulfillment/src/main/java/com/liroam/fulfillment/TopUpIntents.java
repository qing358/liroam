package com.liroam.fulfillment;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 加包选卡上下文（{@code lr_topup_intent}）。下单时由 H2 绑定订单号（P2）；支付后据此确定加包挂到哪张卡。 */
public class TopUpIntents {

    private final JdbcClient jdbc;

    public TopUpIntents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> boundCard(String orderSn, String shellSkuId) {
        return jdbc.sql("""
                SELECT card_asset_id FROM lr_topup_intent
                 WHERE li_order_sn = ? AND li_goods_sku_id = ? AND status = 'BOUND'
                 ORDER BY id DESC LIMIT 1
                """).params(orderSn, shellSkuId).query(Long.class).optional();
    }
}
