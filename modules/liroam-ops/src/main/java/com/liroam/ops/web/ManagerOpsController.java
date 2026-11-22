package com.liroam.ops.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.ops.recon.PaymentReconciliation;
import com.liroam.ops.recon.ReconRuns;
import com.liroam.ops.recon.ReconRuns.Diff;
import com.liroam.ops.recon.ReconRuns.Run;
import com.liroam.ops.recon.SupplierReconciliation;
import com.liroam.ops.report.OpsReports;
import com.liroam.ops.report.OpsReports.Overview;

/** 后台运营：基础报表、支付 / 供应商日对账（导入账单文本）、差异处理。 */
@RestController
@RequestMapping("/manager/liroam")
public class ManagerOpsController {

    public record PaymentStatement(String day, String csv) {
    }

    public record SupplierStatement(Long supplierId, String day, String csv) {
    }

    public record Resolution(String status, String resolution) {
    }

    private final CurrentActor actor;
    private final OpsReports reports;
    private final PaymentReconciliation payments;
    private final SupplierReconciliation suppliers;
    private final ReconRuns runs;

    public ManagerOpsController(CurrentActor actor, OpsReports reports, PaymentReconciliation payments, SupplierReconciliation suppliers,
            ReconRuns runs) {
        this.actor = actor;
        this.reports = reports;
        this.payments = payments;
        this.suppliers = suppliers;
        this.runs = runs;
    }

    @GetMapping("/reports/overview")
    public ApiResult<Overview> overview(@RequestParam String from, @RequestParam String to) {
        actor.requireOperator();
        return ApiResult.ok(reports.overview(date(from), date(to)));
    }

    @GetMapping("/recon/runs")
    public ApiResult<PageResult<Run>> runs(@RequestParam(required = false) String kind, @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        actor.requireOperator();
        return ApiResult.ok(runs.page(kind, pageNumber, pageSize));
    }

    @GetMapping("/recon/runs/{id}/diffs")
    public ApiResult<List<Diff>> diffs(@PathVariable long id) {
        actor.requireOperator();
        return ApiResult.ok(runs.diffs(id));
    }

    @PostMapping("/recon/payment")
    public ApiResult<Run> payment(@RequestBody(required = false) PaymentStatement body) {
        String operator = actor.requireOperator();
        if (body == null) {
            throw new UserFacingException("请选择对账日期并导入账单");
        }
        return ApiResult.ok(payments.run(date(body.day()), body.csv(), operator));
    }

    @PostMapping("/recon/supplier")
    public ApiResult<Run> supplier(@RequestBody(required = false) SupplierStatement body) {
        String operator = actor.requireOperator();
        if (body == null || body.supplierId() == null) {
            throw new UserFacingException("请选择供应商、对账日期并导入账单");
        }
        return ApiResult.ok(suppliers.run(body.supplierId(), date(body.day()), body.csv(), operator));
    }

    @PostMapping("/recon/diffs/{id}/resolve")
    public ApiResult<Diff> resolve(@PathVariable long id, @RequestBody(required = false) Resolution body) {
        String operator = actor.requireOperator();
        return ApiResult.ok(runs.resolve(id, body == null ? null : body.status(), body == null ? null : body.resolution(), operator));
    }

    private static LocalDate date(String raw) {
        try {
            return LocalDate.parse(raw == null ? "" : raw.trim());
        } catch (DateTimeParseException e) {
            throw new UserFacingException("日期格式应为 yyyy-MM-dd");
        }
    }
}
