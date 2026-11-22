package com.liroam.fulfillment;

import java.util.Objects;
import java.util.Optional;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBinding.ListingState;
import com.liroam.catalog.SkuBinding.SkuKind;
import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.Supplier;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.fulfillment.FulfillmentRejected.Reason;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.TopUpView;

/**
 * 履约入口的一次性校验（domain-design §7.1）。加包页（{@code TopUpIntents.open}、卡投影的 {@code canTopUp}）、
 * 下单校验（H2）与 {@link FulfillmentEntry} 调同一份规则。含号码 SKU 的全局可售开关随 H2 接入。
 */
public class FulfillmentRules {

    private final SkuBindings bindings;
    private final SupplierDirectory suppliers;
    private final CardAssets cards;

    public FulfillmentRules(SkuBindings bindings, SupplierDirectory suppliers, CardAssets cards) {
        this.bindings = bindings;
        this.suppliers = suppliers;
        this.cards = cards;
    }

    public void verify(FulfillmentRequest r) {
        if (r.qty() <= 0) {
            throw new FulfillmentRejected(Reason.INVALID_QTY, "qty=" + r.qty());
        }
        SkuBinding sku = bindings.findById(r.skuBindingId())
                .orElseThrow(() -> new FulfillmentRejected(Reason.SKU_NOT_BOUND, "sku_binding_id=" + r.skuBindingId()));
        if (sku.listingState() != ListingState.LISTED) {
            throw new FulfillmentRejected(Reason.SKU_NOT_LISTED, "sku=" + sku.shellSkuId() + " state=" + sku.listingState());
        }
        SkuKind expectedKind = r.sourceType() == SourceType.TOPUP ? SkuKind.TOP_UP : SkuKind.NEW_CARD;
        if (sku.skuKind() != expectedKind) {
            throw new FulfillmentRejected(Reason.SKU_KIND_MISMATCH, r.sourceType() + " 不能履约 " + sku.skuKind() + " SKU " + sku.shellSkuId());
        }
        Supplier supplier = suppliers.find(sku.supplierId()).filter(s -> s.enabled() && s.listingEnabled())
                .orElseThrow(() -> new FulfillmentRejected(Reason.SUPPLIER_NOT_LISTABLE, "supplier_id=" + sku.supplierId()));
        if (r.sourceType() == SourceType.TOPUP) {
            if (r.topupCardAssetId() == null) {
                throw new FulfillmentRejected(Reason.TOPUP_CARD_REQUIRED, r.sourceLineRef());
            }
            if (r.qty() != 1) {
                throw new FulfillmentRejected(Reason.TOPUP_QTY_NOT_ONE, "qty=" + r.qty());
            }
            long cardId = r.topupCardAssetId();
            TopUpView card = cards.findForTopUp(cardId)
                    .orElseThrow(() -> new FulfillmentRejected(Reason.TOPUP_CARD_NOT_FOUND, "card_asset_id=" + cardId));
            topUpBlocker(card, r.ownerMemberId(), supplier.id())
                    .ifPresent(reason -> {
                        throw new FulfillmentRejected(reason, "card_asset_id=" + cardId + " status=" + card.status());
                    });
        }
    }

    /** 加包挂卡的四拒（CT-FUL-02）；{@code skuSupplierId} 为加包 SKU 的供应商。 */
    public Optional<Reason> topUpBlocker(TopUpView card, String memberId, long skuSupplierId) {
        if (card.ownerMemberId() == null || !Objects.equals(card.ownerMemberId(), memberId)) {
            return Optional.of(Reason.TOPUP_CARD_NOT_OWNED);
        }
        if (card.supplierId() != skuSupplierId) {
            return Optional.of(Reason.TOPUP_CARD_OTHER_SUPPLIER);
        }
        if (card.refundHold()) {
            return Optional.of(Reason.TOPUP_CARD_REFUND_HOLD);
        }
        if (CardAssetStatus.MACHINE.isTerminal(card.status())) {
            return Optional.of(Reason.TOPUP_CARD_CLOSED);
        }
        return Optional.empty();
    }
}
