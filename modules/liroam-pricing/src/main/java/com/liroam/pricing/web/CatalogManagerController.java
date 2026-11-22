package com.liroam.pricing.web;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.catalog.SkuBinding.KycGateMode;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.catalog.CatalogAdmin;
import com.liroam.pricing.catalog.CatalogAdmin.CardProductRow;
import com.liroam.pricing.catalog.CatalogAdmin.NewOffering;
import com.liroam.pricing.catalog.CatalogAdmin.NewPlanTemplate;
import com.liroam.pricing.catalog.CatalogAdmin.NewSku;
import com.liroam.pricing.catalog.CatalogAdmin.OfferingRow;
import com.liroam.pricing.catalog.CatalogAdmin.PlanTemplateRow;
import com.liroam.pricing.catalog.CatalogAdmin.SkuRow;

/** 后台「旅行卡商品」：卡产品（含号码）、套餐模板、套餐映射、SKU 绑定与上下架。售价仍走 {@code /manager/liroam/sku-bindings/{id}/price}。 */
@RestController
@RequestMapping("/manager/liroam/catalog")
public class CatalogManagerController {

    public record HasNumberRequest(boolean hasNumber) {
    }

    public record SoftRequest(boolean enabled) {
    }

    public record GatesRequest(KycGateMode kycGateMode, boolean numberedGate) {
    }

    private final CurrentActor actor;
    private final CatalogAdmin admin;

    public CatalogManagerController(CurrentActor actor, CatalogAdmin admin) {
        this.actor = actor;
        this.admin = admin;
    }

    @GetMapping("/card-products")
    public ApiResult<List<CardProductRow>> cardProducts() {
        actor.requireOperator();
        return ApiResult.ok(admin.cardProducts());
    }

    @PutMapping("/card-products/{id}/has-number")
    public ApiResult<Void> setHasNumber(@PathVariable long id, @RequestBody HasNumberRequest body) {
        admin.setHasNumber(id, body != null && body.hasNumber(), actor.requireOperator());
        return ApiResult.ok(null);
    }

    @PutMapping("/card-products/{id}/soft")
    public ApiResult<Void> setSoftEnabled(@PathVariable long id, @RequestBody SoftRequest body) {
        admin.setSoftEnabled(id, body != null && body.enabled(), actor.requireOperator());
        return ApiResult.ok(null);
    }

    @GetMapping("/plan-templates")
    public ApiResult<List<PlanTemplateRow>> planTemplates() {
        actor.requireOperator();
        return ApiResult.ok(admin.planTemplates());
    }

    @PostMapping("/plan-templates")
    public ApiResult<Long> createPlanTemplate(@RequestBody NewPlanTemplate body) {
        return ApiResult.ok(admin.createPlanTemplate(body, actor.requireOperator()));
    }

    @GetMapping("/offerings")
    public ApiResult<List<OfferingRow>> offerings() {
        actor.requireOperator();
        return ApiResult.ok(admin.offerings());
    }

    @PostMapping("/offerings")
    public ApiResult<Long> createOffering(@RequestBody NewOffering body) {
        String operator = actor.requireOperator();
        if (body == null) {
            throw new UserFacingException("请填写套餐映射");
        }
        return ApiResult.ok(admin.createOffering(body, operator));
    }

    @GetMapping("/skus")
    public ApiResult<List<SkuRow>> skus() {
        actor.requireOperator();
        return ApiResult.ok(admin.skus());
    }

    @PostMapping("/skus")
    public ApiResult<Long> createSku(@RequestBody NewSku body) {
        return ApiResult.ok(admin.createSku(body, actor.requireOperator()));
    }

    @PutMapping("/skus/{id}/gates")
    public ApiResult<Void> updateGates(@PathVariable long id, @RequestBody GatesRequest body) {
        String operator = actor.requireOperator();
        admin.updateGates(id, body == null ? null : body.kycGateMode(), body != null && body.numberedGate(), operator);
        return ApiResult.ok(null);
    }

    @PostMapping("/skus/{id}/list")
    public ApiResult<Void> list(@PathVariable long id) {
        admin.list(id, actor.requireOperator());
        return ApiResult.ok(null);
    }

    @PostMapping("/skus/{id}/delist")
    public ApiResult<Void> delist(@PathVariable long id) {
        admin.delist(id, actor.requireOperator());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/skus/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        admin.delete(id, actor.requireOperator());
        return ApiResult.ok(null);
    }
}
