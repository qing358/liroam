package com.liroam.fulfillment;

import java.util.Objects;
import java.util.Optional;

import com.liroam.catalog.CatalogSettings;
import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBinding.ListingState;
import com.liroam.catalog.SkuBinding.SkuKind;
import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentRejected.Reason;
import com.liroam.fulfillment.FulfillmentRequest.SourceType;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardAssets.TopUpView;

/**
 * 履约入口的一次性校验（domain-design §7.1）。加包页（{@code TopUpIntents.open}、卡投影的 {@code canTopUp}）、
 * 下单校验（H2）与 {@link FulfillmentEntry} 调同一份规则；含号码 SKU 另受全局开关 {@link CatalogSettings#NUMBERED_SKU_ENABLED}。
 */
public class FulfillmentRules {

    private final SkuBindings bindings;
    private final SupplierDirectory suppliers;
    private final CardAssets cards;
    private final Settings settings;

    public FulfillmentRules(SkuBindings bindings, SupplierDirectory suppliers, CardAssets cards, Settings settings) {
        this.bindings = bindings;
        this.suppliers = suppliers;
        this.cards = cards;
        this.settings = settings;
    }

    public void verify(FulfillmentRequest r) {
        if (r.qty() <= 0) {
            throw new FulfillmentRejected(Reason.INVALID_QTY, "qty=" + r.qty());
        }
        SkuKind expectedKind = r.sourceType() == SourceType.TOPUP ? SkuKind.TOP_UP : SkuKind.NEW_CARD;
        SkuBinding sku = sellable(bindings.findById(r.skuBindingId()), "sku_binding_id=" + r.skuBindingId(), expectedKind);
        if (r.sourceType() == SourceType.TOPUP) {
            if (r.topupCardAssetId() == null) {
                throw new FulfillmentRejected(Reason.TOPUP_CARD_REQUIRED, r.sourceLineRef());
            }
            if (r.qty() != 1) {
                throw new FulfillmentRejected(Reason.TOPUP_QTY_NOT_ONE, "qty=" + r.qty());
            }
            topUpCard(sku, r.ownerMemberId(), r.topupCardAssetId());
        }
    }

    /** 加包页选卡（{@code TopUpIntents.open}）：与 {@link #verify} 的 TOPUP 分支同一组检查、同一份拒绝原因。 */
    public SkuBinding verifyTopUp(String shellSkuId, String memberId, Long cardAssetId) {
        SkuBinding sku = sellable(bindings.findByShellSkuId(shellSkuId), "li_goods_sku_id=" + shellSkuId, SkuKind.TOP_UP);
        if (cardAssetId == null) {
            throw new FulfillmentRejected(Reason.TOPUP_CARD_REQUIRED, "li_goods_sku_id=" + shellSkuId);
        }
        topUpCard(sku, memberId, cardAssetId);
        return sku;
    }

    private SkuBinding sellable(Optional<SkuBinding> found, String ref, SkuKind expectedKind) {
        SkuBinding sku = found.orElseThrow(() -> new FulfillmentRejected(Reason.SKU_NOT_BOUND, ref));
        if (sku.listingState() != ListingState.LISTED) {
            throw new FulfillmentRejected(Reason.SKU_NOT_LISTED, "sku=" + sku.shellSkuId() + " state=" + sku.listingState());
        }
        if (sku.skuKind() != expectedKind) {
            throw new FulfillmentRejected(Reason.SKU_KIND_MISMATCH, expectedKind + " 不能用 " + sku.skuKind() + " SKU " + sku.shellSkuId());
        }
        suppliers.find(sku.supplierId()).filter(s -> s.enabled() && s.listingEnabled())
                .orElseThrow(() -> new FulfillmentRejected(Reason.SUPPLIER_NOT_LISTABLE, "supplier_id=" + sku.supplierId()));
        if (sku.numberedGate() && !settings.get(CatalogSettings.NUMBERED_SKU_ENABLED)) {
            throw new FulfillmentRejected(Reason.NUMBERED_SKU_DISABLED, "sku=" + sku.shellSkuId());
        }
        return sku;
    }

    private void topUpCard(SkuBinding sku, String memberId, long cardId) {
        TopUpView card = cards.findForTopUp(cardId)
                .orElseThrow(() -> new FulfillmentRejected(Reason.TOPUP_CARD_NOT_FOUND, "card_asset_id=" + cardId));
        topUpBlocker(card, memberId, sku.supplierId())
                .ifPresent(reason -> {
                    throw new FulfillmentRejected(reason, "card_asset_id=" + cardId + " status=" + card.status());
                });
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
