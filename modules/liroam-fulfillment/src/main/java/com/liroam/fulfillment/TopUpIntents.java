package com.liroam.fulfillment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.SkuBinding;
import com.liroam.common.time.UtcColumns;

/**
 * 加包选卡上下文（{@code lr_topup_intent}）：加包页选卡时 {@link #open}（与履约入口同一组校验），
 * 下单时由 H2 {@link #bind} 把最近一次选卡绑到订单号；支付后 {@link #boundCard} 确定加包挂到哪张卡。
 */
public class TopUpIntents {

    /** @param skuId 壳 {@code li_goods_sku.id}；@param goodsId 壳 {@code li_goods.id}（前端下单用） */
    public record Opened(long intentId, String skuId, String goodsId) {
    }

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final FulfillmentRules rules;
    private final Clock clock;

    public TopUpIntents(TransactionOperations tx, JdbcClient jdbc, FulfillmentRules rules, Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.rules = rules;
        this.clock = clock;
    }

    /** @throws FulfillmentRejected 与 {@link FulfillmentEntry#accept} 的加包拒绝同一原因、同一文案 */
    public Opened open(String memberId, long cardAssetId, String liGoodsSkuId) {
        SkuBinding sku = rules.verifyTopUp(liGoodsSkuId, memberId, cardAssetId);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        LocalDateTime now = UtcColumns.toColumn(clock.instant());
        jdbc.sql("""
                INSERT INTO lr_topup_intent (member_id, card_asset_id, li_goods_sku_id, status, created_at, updated_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?)
                """).params(memberId, cardAssetId, sku.shellSkuId(), now, now).update(keys);
        return new Opened(keys.getKey().longValue(), sku.shellSkuId(), sku.shellGoodsId());
    }

    /**
     * 下单落库后绑定：该买家该 SKU 最近一条未绑定的意图 → BOUND。同一订单号重复调用返回已绑定的卡。
     *
     * @return 加包要挂的卡；empty = 买家没走选卡页
     */
    public Optional<Long> bind(String liOrderSn, String memberId, String liGoodsSkuId) {
        return tx.execute(status -> boundCard(liOrderSn, liGoodsSkuId).or(() -> jdbc.sql("""
                SELECT id, card_asset_id FROM lr_topup_intent
                 WHERE member_id = ? AND li_goods_sku_id = ? AND status = 'OPEN'
                 ORDER BY id DESC LIMIT 1 FOR UPDATE
                """).params(memberId, liGoodsSkuId).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).optional().map(row -> {
            jdbc.sql("UPDATE lr_topup_intent SET status = 'BOUND', li_order_sn = ?, version = version + 1, updated_at = ? WHERE id = ?")
                    .params(liOrderSn, UtcColumns.toColumn(clock.instant()), row[0]).update();
            return row[1];
        })));
    }

    public Optional<Long> boundCard(String orderSn, String shellSkuId) {
        return jdbc.sql("""
                SELECT card_asset_id FROM lr_topup_intent
                 WHERE li_order_sn = ? AND li_goods_sku_id = ? AND status = 'BOUND'
                 ORDER BY id DESC LIMIT 1
                """).params(orderSn, shellSkuId).query(Long.class).optional();
    }
}
