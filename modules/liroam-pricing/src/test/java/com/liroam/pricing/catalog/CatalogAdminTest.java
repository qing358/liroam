package com.liroam.pricing.catalog;

import static com.liroam.pricing.testing.TestRateSheet.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBinding.KycGateMode;
import com.liroam.catalog.SkuBinding.ListingState;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.catalog.CatalogAdmin.CardProductRow;
import com.liroam.pricing.catalog.CatalogAdmin.NewOffering;
import com.liroam.pricing.catalog.CatalogAdmin.NewPlanTemplate;
import com.liroam.pricing.catalog.CatalogAdmin.NewSku;
import com.liroam.pricing.catalog.CatalogAdmin.SkuRow;
import com.liroam.pricing.catalog.ShellSkuDirectory.ShellSku;
import com.liroam.pricing.testing.PricedCatalog;
import com.liroam.pricing.testing.PricingFixture;
import com.liroam.pricing.testing.TestRateSheet;

/**
 * 后台「旅行卡商品」：套餐模板 → 套餐映射（上游套餐码）→ SKU 绑定（草稿）→ 改闸 → 上架/下架。
 * 绑定从套餐映射带出供应商/卡产品/区域/套餐；形态须在卡产品允许形态内、与壳商品类型一致；闸默认取卡产品含号码与区域是否需实名。
 */
class CatalogAdminTest {

    private PricingFixture f;
    private PricedCatalog c;
    private CatalogAdmin admin;
    private final Map<String, ShellSku> shell = new HashMap<>();

    @BeforeEach
    void setUp() {
        f = PricingFixture.create("pricing_catalog_admin");
        c = PricedCatalog.seed(f);
        admin = new CatalogAdmin(f.client, f.tx, f.bindings, f.prices, id -> Optional.ofNullable(shell.get(id)), f.audit);
        shell.put("G1-ESIM", new ShellSku("G1-ESIM", "G1", "日本卡", ShellSkuDirectory.VIRTUAL_GOODS));
        shell.put("G1-SIM", new ShellSku("G1-SIM", "G1", "日本卡", ShellSkuDirectory.PHYSICAL_GOODS));
        shell.put("G2-HK", new ShellSku("G2-HK", "G2", "香港卡", ShellSkuDirectory.VIRTUAL_GOODS));
    }

    private long voicePlan() {
        return admin.createPlanTemplate(new NewPlanTemplate("日本 30 天 3GB + 100 分钟 + 50 条", 3, 100, 50, 30), "op1");
    }

    private long offering(String region, long plan) {
        return admin.createOffering(new NewOffering(c.supplier, c.product, f.regionId(region), plan, "BASE", "3GB-30D-100MIN-50SMS"), "op1");
    }

    @Test
    void planTemplateStoresQuotaInDomainUnits() {
        long plan = voicePlan();

        Map<String, Object> row = f.jdbc.queryForMap("SELECT data_quota_bytes, voice_seconds, sms_count, validity_days FROM lr_plan_template WHERE id = ?", plan);
        assertThat(row).containsEntry("data_quota_bytes", 3L << 30).containsEntry("voice_seconds", 6000L).containsEntry("sms_count", 50)
                .containsEntry("validity_days", 30);
        assertThatThrownBy(() -> admin.createPlanTemplate(new NewPlanTemplate(" ", 1, null, null, 7), "op1")).hasMessage("请填写套餐名称");
        assertThatThrownBy(() -> admin.createPlanTemplate(new NewPlanTemplate("x", 1, null, null, 0), "op1")).hasMessage("有效天数须大于 0");
    }

    @Test
    void offeringMustUseAnActiveRegionOfTheSuppliersCardProduct() {
        long plan = voicePlan();
        long other = f.supplier("SUP_X");

        assertThat(offering("JP", plan)).isPositive();
        assertThatThrownBy(() -> admin.createOffering(new NewOffering(other, c.product, f.regionId("JP"), plan, "BASE", "P"), "op1"))
                .isInstanceOf(UserFacingException.class).hasMessage("卡产品不属于该供应商");
        f.jdbc.update("INSERT INTO lr_usage_region (code, name_zh, continent) VALUES ('ZZ', '测试', '亚洲')");
        assertThatThrownBy(() -> admin.createOffering(new NewOffering(c.supplier, c.product, f.regionId("ZZ"), plan, "BASE", "P"), "op1"))
                .hasMessage("该卡产品当前资费不覆盖这个使用区域");
        assertThatThrownBy(() -> admin.createOffering(new NewOffering(c.supplier, c.product, f.regionId("JP"), plan, "BASE", " "), "op1"))
                .hasMessage("请填写上游套餐码");
    }

    @Test
    void skuBindingTakesItsShapeFromTheOfferingAndDefaultsGates() {
        long jp = offering("JP", voicePlan());
        long hk = offering("HK", voicePlan());
        f.jdbc.update("UPDATE lr_card_product SET has_number = 1 WHERE id = ?", c.product);

        long esim = admin.createSku(new NewSku("G1-ESIM", jp, "ESIM", null, null), "op1");
        long hkSku = admin.createSku(new NewSku("G2-HK", hk, "ESIM", null, null), "op1");

        SkuBinding b = f.bindings.findById(esim).orElseThrow();
        assertThat(b).extracting(SkuBinding::shellGoodsId, SkuBinding::supplierId, SkuBinding::cardProductId, SkuBinding::regionId, SkuBinding::form,
                SkuBinding::listingState, SkuBinding::numberedGate, SkuBinding::kycGateMode)
                .containsExactly("G1", c.supplier, c.product, f.regionId("JP"), "ESIM", ListingState.DRAFT, true, KycGateMode.NONE);
        assertThat(f.bindings.findById(hkSku).orElseThrow().kycGateMode()).as("区域需实名 → 默认提示").isEqualTo(KycGateMode.PROMPT);
        assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'SKU_BINDING_CREATE'")).isEqualTo(2);
    }

    @Test
    void skuBindingRejectsWrongShellGoodsFormsAndDuplicates() {
        long jp = offering("JP", voicePlan());

        assertThatThrownBy(() -> admin.createSku(new NewSku("NOPE", jp, "ESIM", null, null), "op1")).hasMessage("壳商品 SKU 不存在");
        assertThatThrownBy(() -> admin.createSku(new NewSku("G1-SIM", jp, "ESIM", null, null), "op1"))
                .hasMessage("eSIM / 软卡须绑虚拟商品；实体卡须绑实物商品");
        assertThatThrownBy(() -> admin.createSku(new NewSku("G1-ESIM", jp, "SOFT", null, null), "op1")).hasMessage("该卡产品不支持这种卡形态");
        assertThat(admin.createSku(new NewSku("G1-SIM", jp, "PHYSICAL", null, null), "op1")).isPositive();
        admin.createSku(new NewSku("G1-ESIM", jp, "ESIM", null, null), "op1");
        assertThatThrownBy(() -> admin.createSku(new NewSku("G1-ESIM", jp, "ESIM", null, null), "op1")).hasMessage("这个壳 SKU 已绑定旅行卡");
    }

    @Test
    void listingNeedsActivePriceAndListableSupplierThenLocks() {
        long sku = admin.createSku(new NewSku("G1-ESIM", offering("JP", voicePlan()), "ESIM", null, null), "op1");

        assertThatThrownBy(() -> admin.list(sku, "op1")).hasMessage("请先设置售价");
        f.setFx("0.9");
        f.fxSync.run("op1");
        f.prices.activateManual(sku, 3900, "op1");
        f.jdbc.update("UPDATE lr_supplier SET listing_enabled = 0 WHERE id = ?", c.supplier);
        assertThatThrownBy(() -> admin.list(sku, "op1")).hasMessage("该供应商未开放上架");
        f.jdbc.update("UPDATE lr_supplier SET listing_enabled = 1 WHERE id = ?", c.supplier);

        admin.updateGates(sku, KycGateMode.BLOCK_DELIVERY, false, "op1");
        admin.list(sku, "op1");

        SkuRow row = admin.skus().stream().filter(r -> r.id() == sku).findFirst().orElseThrow();
        assertThat(row).extracting(SkuRow::listingState, SkuRow::kycGateMode, SkuRow::priceFen, SkuRow::locked, SkuRow::planName)
                .containsExactly("LISTED", "BLOCK_DELIVERY", 3900L, true, "日本 30 天 3GB + 100 分钟 + 50 条");
        assertThatThrownBy(() -> admin.delete(sku, "op1")).hasMessage("只能删除从未上架的草稿");
        admin.delist(sku, "op1");
        assertThat(f.bindings.findById(sku).orElseThrow().listingState()).isEqualTo(ListingState.DELISTED);
        admin.list(sku, "op1");
        assertThat(f.bindings.findById(sku).orElseThrow().listingState()).isEqualTo(ListingState.LISTED);
    }

    private void activateSheetWithSoftFee() {
        byte[] xlsx = new TestRateSheet().data("R1", true,
                row("亚洲", "日本", "KDDI", "10", "-"),
                row("亚洲", "中国", "CMCC", "6", "不支持纯软卡文件 (ii)"),
                row("亚洲", "香港", "CSL", "5", "实名认证"))
                .cardFee("eSIM", "2").cardFee("实体卡", "1").cardFee("纯软件卡Profile", "0.5")
                .notes("(i) 实名认证:\nR1在香港使用前须实名。\n\n(ii) NO Soft-Profile:\n不支持软配置文件")
                .build();
        f.tariffs.activate(f.tariffs.importXlsx(c.supplier, "soft.xlsx", new ByteArrayInputStream(xlsx), "manager:ops"), "manager:ops");
    }

    private CardProductRow product() {
        return admin.cardProducts().stream().filter(p -> p.id() == c.product).findFirst().orElseThrow();
    }

    @Test
    void softFormIsOpenedByOpsOnlyWithSoftCardFeeAndNotInRegionsMarkedUnsupported() {
        assertThat(product()).extracting(CardProductRow::allowedForms, CardProductRow::softFeeAvailable).containsExactly("ESIM,PHYSICAL", false);
        assertThatThrownBy(() -> admin.setSoftEnabled(c.product, true, "op1")).hasMessage("当前资费表没有「纯软件卡 Profile」卡费，不能开卖软卡");

        activateSheetWithSoftFee();
        assertThat(product()).extracting(CardProductRow::allowedForms, CardProductRow::softFeeAvailable).as("有软卡卡费也不自动开卖")
                .containsExactly("ESIM,PHYSICAL", true);
        long jp = offering("JP", voicePlan());
        assertThatThrownBy(() -> admin.createSku(new NewSku("G1-ESIM", jp, "SOFT", null, null), "op1")).hasMessage("该卡产品不支持这种卡形态");

        admin.setSoftEnabled(c.product, true, "op1");
        assertThat(product().allowedForms()).isEqualTo("ESIM,PHYSICAL,SOFT");
        assertThat(f.count("SELECT COUNT(*) FROM lr_audit_log WHERE action = 'CARD_PRODUCT_UPDATE' AND after_json LIKE '%soft%'")).isEqualTo(1);

        assertThatThrownBy(() -> admin.createSku(new NewSku("G1-ESIM", offering("CN", voicePlan()), "SOFT", null, null), "op1"))
                .hasMessage("「中国」不支持纯软卡（资费表注明），请改用 eSIM 或实体卡");
        long soft = admin.createSku(new NewSku("G1-ESIM", jp, "SOFT", null, null), "op1");
        f.setFx("0.9");
        f.fxSync.run("op1");
        assertThat(f.costs.quote(soft).breakdownJson()).as("软卡按纯软件卡卡费计成本").contains("纯软件卡Profile");
        f.prices.activateManual(soft, 3900, "op1");
        admin.list(soft, "op1");

        assertThatThrownBy(() -> admin.setSoftEnabled(c.product, false, "op1")).hasMessage("该卡产品还有在售的软卡商品，请先下架");
        admin.delist(soft, "op1");
        admin.setSoftEnabled(c.product, false, "op1");
        assertThat(product().allowedForms()).isEqualTo("ESIM,PHYSICAL");
        assertThatThrownBy(() -> admin.list(soft, "op1")).hasMessage("该卡产品已不支持这种卡形态，不能上架");
    }

    @Test
    void draftCanBeDeleted() {
        long sku = admin.createSku(new NewSku("G1-ESIM", offering("JP", voicePlan()), "ESIM", KycGateMode.NONE, false), "op1");

        admin.delete(sku, "op1");

        assertThat(f.bindings.findById(sku)).isEmpty();
    }
}
