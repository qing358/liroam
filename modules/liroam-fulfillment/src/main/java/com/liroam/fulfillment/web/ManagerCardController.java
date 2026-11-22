package com.liroam.fulfillment.web;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.card.AdminCardView;
import com.liroam.fulfillment.card.CardKyc;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.card.CardQueries.CardFilter;
import com.liroam.fulfillment.credential.CredentialEntry;
import com.liroam.fulfillment.credential.CredentialImports;
import com.liroam.fulfillment.credential.Shipment;
import com.liroam.fulfillment.pool.CardPool;
import com.liroam.fulfillment.state.KycStatus;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.fulfillment.task.ManualTasks.TaskView;

/** 后台卡台账：分页筛选、详情（含指令历史）、人工录码、实体卡录物流、实名核验、交码导入、卡池导入、人工待办。 */
@RestController
@RequestMapping("/manager/liroam/cards")
public class ManagerCardController {

    public record CredentialRequest(String lpa) {

        @Override
        public String toString() {
            return "CredentialRequest[lpa=***]";
        }
    }

    public record ResolveRequest(String note) {
    }

    /** @param logisticsId 壳物流公司 id（{@code li_logistics.id}） */
    public record ShipmentRequest(String logisticsId, String logisticsName, String logisticsNo) {
    }

    /** @param result VERIFIED / REJECTED */
    public record KycReviewRequest(KycStatus result, String note) {
    }

    private final CurrentActor actor;
    private final CardQueries queries;
    private final CredentialEntry entry;
    private final CredentialImports imports;
    private final CardPool pool;
    private final ManualTasks tasks;
    private final CardKyc kyc;

    public ManagerCardController(CurrentActor actor, CardQueries queries, CredentialEntry entry, CredentialImports imports, CardPool pool,
            ManualTasks tasks, CardKyc kyc) {
        this.actor = actor;
        this.queries = queries;
        this.entry = entry;
        this.imports = imports;
        this.pool = pool;
        this.tasks = tasks;
        this.kyc = kyc;
    }

    @GetMapping
    public ApiResult<PageResult<AdminCardView>> page(@RequestParam(defaultValue = "1") int pageNumber, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status, @RequestParam(required = false) String supplierCode,
            @RequestParam(required = false) String keyword) {
        actor.requireOperator();
        return ApiResult.ok(queries.page(new CardFilter(status, supplierCode, keyword, pageNumber, pageSize)));
    }

    @GetMapping("/{id}")
    public ApiResult<AdminCardView.Detail> detail(@PathVariable long id) {
        actor.requireOperator();
        return ApiResult.ok(queries.detail(id).orElseThrow(() -> new UserFacingException("找不到这张卡")));
    }

    @PostMapping("/{id}/credential")
    public ApiResult<Void> enterCredential(@PathVariable long id, @RequestBody CredentialRequest body) {
        entry.enter(id, body == null ? null : body.lpa(), actor.requireOperator());
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/shipment")
    public ApiResult<Void> enterShipment(@PathVariable long id, @RequestBody ShipmentRequest body) {
        entry.enterShipment(id, body == null ? null : new Shipment(body.logisticsId(), body.logisticsName(), body.logisticsNo()),
                actor.requireOperator());
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/kyc")
    public ApiResult<Void> reviewKyc(@PathVariable long id, @RequestBody KycReviewRequest body) {
        kyc.review(id, body == null ? null : body.result(), body == null ? null : body.note(), actor.requireOperator());
        return ApiResult.ok(null);
    }

    /** CSV：{@code iccid,lpa}，首行表头可有可无。 */
    @PostMapping("/credentials/import")
    public ApiResult<CredentialImports.ImportReport> importCredentials(@RequestParam("file") MultipartFile file) throws IOException {
        String operator = actor.requireOperator();
        try (Reader csv = reader(file)) {
            return ApiResult.ok(imports.importCsv(csv, operator));
        }
    }

    /** CSV：{@code iccid[,lpa]}，导入到指定供应商的卡产品下；{@code form} 不填按 eSIM 优先，实体卡库存传 PHYSICAL。 */
    @PostMapping("/pool/import")
    public ApiResult<CardPool.ImportReport> importPool(@RequestParam("file") MultipartFile file, @RequestParam long supplierId,
            @RequestParam long cardProductId, @RequestParam(required = false) String form) throws IOException {
        String operator = actor.requireOperator();
        try (Reader csv = reader(file)) {
            return ApiResult.ok(pool.importItems(supplierId, cardProductId, form, CardPool.parseCsv(csv), operator));
        }
    }

    @GetMapping("/manual-tasks")
    public ApiResult<List<TaskView>> manualTasks(@RequestParam(required = false) ManualTasks.Status status) {
        actor.requireOperator();
        return ApiResult.ok(tasks.views(status));
    }

    @PostMapping("/manual-tasks/{id}/resolve")
    public ApiResult<Void> resolve(@PathVariable long id, @RequestBody(required = false) ResolveRequest body) {
        tasks.resolve(id, body == null ? null : body.note(), actor.requireOperator());
        return ApiResult.ok(null);
    }

    private static Reader reader(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new UserFacingException("请上传 CSV 文件");
        }
        return new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
    }
}
