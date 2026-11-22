package com.liroam.fulfillment.store;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.fulfillment.state.CardAssetStatus;

/** 卡资产仓储；只允许 fulfillment 模块使用（架构检查）。 */
public class CardAssets {

    /** 加包校验需要的卡属性。 */
    public record TopUpView(long id, String ownerMemberId, long supplierId, CardAssetStatus status, boolean refundHold) {
    }

    private final JdbcClient jdbc;

    public CardAssets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TopUpView> findForTopUp(long cardAssetId) {
        return jdbc.sql("SELECT id, owner_member_id, supplier_id, status, refund_hold FROM lr_card_asset WHERE id = ?")
                .param(cardAssetId).query(TopUpView.class).optional();
    }
}
