package com.liroam.pricing.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.catalog.CardForm;
import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBinding.KycGateMode;
import com.liroam.catalog.SkuBinding.ListingState;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.catalog.ShellSkuDirectory.ShellSku;
import com.liroam.pricing.price.PriceVersions;

/**
 * 后台「旅行卡商品」：套餐模板、套餐映射（领域套餐 → 上游套餐码）、SKU 绑定与上下架。
 * 绑定的供应商/卡产品/区域/套餐一律从套餐映射带出，避免两处口径；首次上架后由库触发器锁定。
 */
public class CatalogAdmin {

    private static final long GB = 1024L * 1024 * 1024;

    /** @param dataGb null = 不限量 */
    public record NewPlanTemplate(String name, Integer dataGb, Integer voiceMinutes, Integer smsCount, int validityDays) {
    }

    /** @param kind BASE / TOP_UP */
    public record NewOffering(long supplierId, long cardProductId, long regionId, long planTemplateId, String kind, String upstreamPackageCode) {
    }

    /** @param kycGateMode null = 区域需实名则 PROMPT，否则 NONE；@param numberedGate null = 跟卡产品「含号码」 */
    public record NewSku(String shellSkuId, long offeringId, String form, KycGateMode kycGateMode, Boolean numberedGate) {
    }

    public record CardProductRow(long id, long supplierId, String supplierCode, String name, boolean capData, boolean capVoice, boolean capSms,
            boolean hasNumber, String allowedForms) {
    }

    public record PlanTemplateRow(long id, String name, Long dataQuotaBytes, Long voiceSeconds, Integer smsCount, int validityDays) {
    }

    public record OfferingRow(long id, long supplierId, String supplierCode, long cardProductId, String cardProductName, Long regionId,
            String regionName, long planTemplateId, String planName, String kind, String upstreamPackageCode, boolean enabled) {
    }

    public record SkuRow(long id, String shellSkuId, String shellGoodsId, String skuKind, String supplierCode, String cardProductName,
            String regionName, String planName, String form, String kycGateMode, boolean numberedGate, String listingState, boolean locked,
            Long priceFen) {
    }

    private record Offering(long id, long supplierId, long cardProductId, Long regionId, long planTemplateId, String kind, String allowedForms,
            boolean hasNumber, Boolean kycRequired) {
    }

    private final JdbcClient jdbc;
    private final TransactionOperations tx;
    private final SkuBindings bindings;
    private final PriceVersions prices;
    private final ShellSkuDirectory shell;
    private final AuditLog audit;

    public CatalogAdmin(JdbcClient jdbc, TransactionOperations tx, SkuBindings bindings, PriceVersions prices, ShellSkuDirectory shell, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.bindings = bindings;
        this.prices = prices;
        this.shell = shell;
        this.audit = audit;
    }

    public List<CardProductRow> cardProducts() {
        return jdbc.sql("""
                SELECT p.id, p.supplier_id, s.code AS supplier_code, p.name, p.cap_data, p.cap_voice, p.cap_sms, p.has_number, p.allowed_forms
                  FROM lr_card_product p JOIN lr_supplier s ON s.id = p.supplier_id ORDER BY p.supplier_id, p.id
                """).query(CardProductRow.class).list();
    }

    /** 含号码由运营按卡产品资料设定（资费表不给）。 */
    public void setHasNumber(long cardProductId, boolean hasNumber, String actor) {
        if (jdbc.sql("UPDATE lr_card_product SET has_number = ? WHERE id = ?").params(hasNumber, cardProductId).update() == 0) {
            throw new UserFacingException("卡产品不存在");
        }
        audit.record(actor, "CARD_PRODUCT_UPDATE", "CARD_PRODUCT", Long.toString(cardProductId), null, "{\"hasNumber\":" + hasNumber + "}");
    }

    public List<PlanTemplateRow> planTemplates() {
        return jdbc.sql("SELECT id, name, data_quota_bytes, voice_seconds, sms_count, validity_days FROM lr_plan_template ORDER BY id DESC")
                .query(PlanTemplateRow.class).list();
    }

    public long createPlanTemplate(NewPlanTemplate t, String actor) {
        if (t == null || t.name() == null || t.name().isBlank()) {
            throw new UserFacingException("请填写套餐名称");
        }
        if (t.validityDays() <= 0) {
            throw new UserFacingException("有效天数须大于 0");
        }
        if (negative(t.dataGb()) || negative(t.voiceMinutes()) || negative(t.smsCount())) {
            throw new UserFacingException("额度不能为负数");
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO lr_plan_template (name, data_quota_bytes, voice_seconds, sms_count, validity_days) VALUES (?, ?, ?, ?, ?)")
                .params(t.name().trim(), t.dataGb() == null ? null : t.dataGb() * GB, t.voiceMinutes() == null ? null : t.voiceMinutes() * 60L,
                        t.smsCount(), t.validityDays())
                .update(keys);
        long id = keys.getKey().longValue();
        audit.record(actor, "PLAN_TEMPLATE_CREATE", "PLAN_TEMPLATE", Long.toString(id), null, null);
        return id;
    }

    public List<OfferingRow> offerings() {
        return jdbc.sql("""
                SELECT o.id, o.supplier_id, s.code AS supplier_code, o.card_product_id, p.name AS card_product_name, o.region_id,
                       r.name_zh AS region_name, o.plan_template_id, t.name AS plan_name, o.kind, o.upstream_package_code, o.enabled
                  FROM lr_supplier_offering o
                  JOIN lr_supplier s ON s.id = o.supplier_id
                  JOIN lr_card_product p ON p.id = o.card_product_id
                  JOIN lr_plan_template t ON t.id = o.plan_template_id
                  LEFT JOIN lr_usage_region r ON r.id = o.region_id
                 ORDER BY o.id DESC
                """).query(OfferingRow.class).list();
    }

    public long createOffering(NewOffering o, String actor) {
        if (o.upstreamPackageCode() == null || o.upstreamPackageCode().isBlank()) {
            throw new UserFacingException("请填写上游套餐码");
        }
        if (!"BASE".equals(o.kind()) && !"TOP_UP".equals(o.kind())) {
            throw new UserFacingException("套餐类别只能是首购或加包");
        }
        Long productSupplier = jdbc.sql("SELECT supplier_id FROM lr_card_product WHERE id = ?").param(o.cardProductId()).query(Long.class).optional()
                .orElseThrow(() -> new UserFacingException("卡产品不存在"));
        if (productSupplier != o.supplierId()) {
            throw new UserFacingException("卡产品不属于该供应商");
        }
        if (jdbc.sql("SELECT COUNT(*) FROM lr_card_product_region WHERE card_product_id = ? AND region_id = ? AND active = 1")
                .params(o.cardProductId(), o.regionId()).query(Integer.class).single() == 0) {
            throw new UserFacingException("该卡产品当前资费不覆盖这个使用区域");
        }
        if (jdbc.sql("SELECT COUNT(*) FROM lr_plan_template WHERE id = ?").param(o.planTemplateId()).query(Integer.class).single() == 0) {
            throw new UserFacingException("套餐模板不存在");
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_supplier_offering (supplier_id, card_product_id, region_id, plan_template_id, kind, upstream_package_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(o.supplierId(), o.cardProductId(), o.regionId(), o.planTemplateId(), o.kind(), o.upstreamPackageCode().trim()).update(keys);
        long id = keys.getKey().longValue();
        audit.record(actor, "OFFERING_CREATE", "OFFERING", Long.toString(id), null, null);
        return id;
    }

    public List<SkuRow> skus() {
        return jdbc.sql("""
                SELECT b.id, b.li_goods_sku_id AS shell_sku_id, b.li_goods_id AS shell_goods_id, b.sku_kind, s.code AS supplier_code,
                       p.name AS card_product_name, COALESCE(r.name_zh, g.name_zh) AS region_name, t.name AS plan_name, b.form, b.kyc_gate_mode,
                       b.numbered_gate, b.listing_state, b.locked_at IS NOT NULL AS locked, v.price_cny_fen AS price_fen
                  FROM lr_sku_binding b
                  JOIN lr_supplier s ON s.id = b.supplier_id
                  JOIN lr_card_product p ON p.id = b.card_product_id
                  JOIN lr_plan_template t ON t.id = b.plan_template_id
                  LEFT JOIN lr_usage_region r ON r.id = b.region_id
                  LEFT JOIN lr_region_group g ON g.id = b.region_group_id
                  LEFT JOIN lr_sku_price_version v ON v.sku_binding_id = b.id AND v.status = 'ACTIVE'
                 ORDER BY b.id DESC
                """).query(SkuRow.class).list();
    }

    public long createSku(NewSku n, String actor) {
        if (n == null || n.shellSkuId() == null || n.shellSkuId().isBlank()) {
            throw new UserFacingException("请填写壳商品 SKU");
        }
        String skuId = n.shellSkuId().trim();
        Offering o = jdbc.sql("""
                SELECT o.id, o.supplier_id, o.card_product_id, o.region_id, o.plan_template_id, o.kind, p.allowed_forms, p.has_number,
                       cpr.kyc_required
                  FROM lr_supplier_offering o
                  JOIN lr_card_product p ON p.id = o.card_product_id
                  LEFT JOIN lr_card_product_region cpr ON cpr.card_product_id = o.card_product_id AND cpr.region_id = o.region_id AND cpr.active = 1
                 WHERE o.id = ? AND o.enabled = 1
                """).param(n.offeringId()).query(Offering.class).optional().orElseThrow(() -> new UserFacingException("套餐映射不存在或已停用"));
        CardForm form = form(n.form(), o.allowedForms());
        boolean topUp = "TOP_UP".equals(o.kind());
        if (topUp && form.shipsPhysically()) {
            throw new UserFacingException("加包不能绑实体卡形态");
        }
        ShellSku goods = shell.find(skuId).orElseThrow(() -> new UserFacingException("壳商品 SKU 不存在"));
        String expectedType = form.shipsPhysically() ? ShellSkuDirectory.PHYSICAL_GOODS : ShellSkuDirectory.VIRTUAL_GOODS;
        if (!expectedType.equals(goods.goodsType())) {
            throw new UserFacingException("eSIM / 软卡须绑虚拟商品；实体卡须绑实物商品");
        }
        if (bindings.findByShellSkuId(skuId).isPresent()) {
            throw new UserFacingException("这个壳 SKU 已绑定旅行卡");
        }
        KycGateMode kyc = n.kycGateMode() != null ? n.kycGateMode() : Boolean.TRUE.equals(o.kycRequired()) ? KycGateMode.PROMPT : KycGateMode.NONE;
        boolean numbered = n.numberedGate() != null ? n.numberedGate() : o.hasNumber();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_sku_binding (li_goods_sku_id, li_goods_id, sku_kind, supplier_id, card_product_id, region_id, form, plan_template_id,
                                            offering_id, kyc_gate_mode, numbered_gate, listing_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                """).params(skuId, goods.goodsId(), topUp ? "TOP_UP" : "NEW_CARD", o.supplierId(), o.cardProductId(), o.regionId(), form.name(),
                o.planTemplateId(), o.id(), kyc.name(), numbered).update(keys);
        long id = keys.getKey().longValue();
        audit.record(actor, "SKU_BINDING_CREATE", "SKU_BINDING", Long.toString(id), null,
                "{\"sku\":\"" + skuId.replace("\"", "") + "\",\"offering\":" + o.id() + ",\"form\":\"" + form + "\"}");
        return id;
    }

    public void updateGates(long skuBindingId, KycGateMode kycGateMode, boolean numberedGate, String actor) {
        if (kycGateMode == null) {
            throw new UserFacingException("请选择实名闸");
        }
        SkuBinding b = require(skuBindingId);
        jdbc.sql("UPDATE lr_sku_binding SET kyc_gate_mode = ?, numbered_gate = ?, version = version + 1 WHERE id = ?")
                .params(kycGateMode.name(), numberedGate, b.id()).update();
        audit.record(actor, "SKU_BINDING_GATES", "SKU_BINDING", Long.toString(b.id()),
                "{\"kyc\":\"" + b.kycGateMode() + "\",\"numbered\":" + b.numberedGate() + "}",
                "{\"kyc\":\"" + kycGateMode + "\",\"numbered\":" + numberedGate + "}");
    }

    public void list(long skuBindingId, String actor) {
        tx.executeWithoutResult(s -> {
            SkuBinding b = require(skuBindingId);
            if (b.listingState() == ListingState.LISTED) {
                return;
            }
            boolean listable = jdbc.sql("SELECT COUNT(*) FROM lr_supplier WHERE id = ? AND enabled = 1 AND listing_enabled = 1")
                    .param(b.supplierId()).query(Integer.class).single() > 0;
            if (prices.active(b.id()).isEmpty()) {
                throw new UserFacingException("请先设置售价");
            }
            if (!listable) {
                throw new UserFacingException("该供应商未开放上架");
            }
            move(b, ListingState.LISTED, actor);
        });
    }

    public void delist(long skuBindingId, String actor) {
        SkuBinding b = require(skuBindingId);
        if (b.listingState() == ListingState.LISTED) {
            move(b, ListingState.DELISTED, actor);
        }
    }

    /** 只删从未上架的草稿（连同它的成本/售价/调价建议）；上过架的只能下架。 */
    public void delete(long skuBindingId, String actor) {
        tx.executeWithoutResult(s -> {
            boolean draft = jdbc.sql("SELECT COUNT(*) FROM lr_sku_binding WHERE id = ? AND listing_state = 'DRAFT' AND locked_at IS NULL")
                    .param(require(skuBindingId).id()).query(Integer.class).single() > 0;
            if (!draft) {
                throw new UserFacingException("只能删除从未上架的草稿");
            }
            for (String table : List.of("lr_reprice_suggestion", "lr_sku_price_version", "lr_sku_cost_version")) {
                jdbc.sql("DELETE FROM " + table + " WHERE sku_binding_id = ?").param(skuBindingId).update();
            }
            jdbc.sql("DELETE FROM lr_sku_binding WHERE id = ?").param(skuBindingId).update();
            audit.record(actor, "SKU_BINDING_DELETE", "SKU_BINDING", Long.toString(skuBindingId), null, null);
        });
    }

    private void move(SkuBinding b, ListingState to, String actor) {
        jdbc.sql("UPDATE lr_sku_binding SET listing_state = ?, version = version + 1 WHERE id = ?").params(to.name(), b.id()).update();
        audit.record(actor, "SKU_BINDING_" + to, "SKU_BINDING", Long.toString(b.id()), "{\"state\":\"" + b.listingState() + "\"}",
                "{\"state\":\"" + to + "\"}");
    }

    private SkuBinding require(long id) {
        return bindings.findById(id).orElseThrow(() -> new UserFacingException("找不到这个旅行卡商品"));
    }

    private static CardForm form(String requested, String allowedForms) {
        CardForm form;
        try {
            form = CardForm.of(requested);
        } catch (IllegalArgumentException e) {
            throw new UserFacingException("请选择卡形态");
        }
        List<String> allowed = Arrays.stream(allowedForms.split(",")).map(f -> f.trim().toUpperCase(Locale.ROOT)).toList();
        if (!allowed.contains(form.name())) {
            throw new UserFacingException("该卡产品不支持这种卡形态");
        }
        return form;
    }

    private static boolean negative(Integer n) {
        return n != null && n < 0;
    }
}
