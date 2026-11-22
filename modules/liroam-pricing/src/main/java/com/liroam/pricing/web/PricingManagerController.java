package com.liroam.pricing.web;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.liroam.common.setting.SettingKey;
import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.PricingSettings;
import com.liroam.pricing.fx.FxRateRow;
import com.liroam.pricing.fx.FxRates;
import com.liroam.pricing.fx.FxSync;
import com.liroam.pricing.fx.FxSyncResult;
import com.liroam.pricing.price.ActivePrice;
import com.liroam.pricing.price.PriceVersions;
import com.liroam.pricing.price.RepriceMode;
import com.liroam.pricing.price.RepriceSuggestionRow;
import com.liroam.pricing.price.RepriceSuggestions;
import com.liroam.pricing.tariff.CardProductRegionRow;
import com.liroam.pricing.tariff.ImportReport;
import com.liroam.pricing.tariff.TariffImports;
import com.liroam.pricing.tariff.TariffSheetRow;

/** 管理端定价接口（子计划 §3）：资费表、可用区域、汇率、调价建议、定价配置。 */
@RestController
@RequestMapping("/manager/liroam")
public class PricingManagerController {

    private final CurrentActor actor;
    private final TariffImports tariffs;
    private final PriceVersions prices;
    private final RepriceSuggestions suggestions;
    private final Settings settings;
    private final FxSync fxSync;
    private final FxRates fxRates;

    public PricingManagerController(CurrentActor actor, TariffImports tariffs, PriceVersions prices, RepriceSuggestions suggestions, Settings settings,
            FxSync fxSync, FxRates fxRates) {
        this.actor = actor;
        this.tariffs = tariffs;
        this.prices = prices;
        this.suggestions = suggestions;
        this.settings = settings;
        this.fxSync = fxSync;
        this.fxRates = fxRates;
    }

    @PostMapping("/tariff-sheets")
    public ApiResult<ImportReport> upload(@RequestParam long supplierId, @RequestPart("file") MultipartFile file) {
        String operator = actor.requireOperator();
        long sheetId;
        try (InputStream in = file.getInputStream()) {
            sheetId = tariffs.importXlsx(supplierId, file.getOriginalFilename(), in, operator);
        } catch (IOException e) {
            throw new UserFacingException("读取上传文件失败，请重试");
        }
        return ApiResult.ok(tariffs.report(sheetId));
    }

    @GetMapping("/tariff-sheets")
    public ApiResult<PageResult<TariffSheetRow>> sheets(@RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "1") int pageNumber, @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResult.ok(tariffs.page(supplierId, pageNumber, pageSize));
    }

    @GetMapping("/tariff-sheets/{id}")
    public ApiResult<ImportReport> sheet(@PathVariable long id) {
        return ApiResult.ok(tariffs.report(id));
    }

    @PostMapping("/tariff-sheets/{id}/activate")
    public ApiResult<ImportReport> activate(@PathVariable long id) {
        return ApiResult.ok(tariffs.activate(id, actor.requireOperator()));
    }

    @GetMapping("/card-products/{id}/regions")
    public ApiResult<List<CardProductRegionRow>> regions(@PathVariable long id) {
        return ApiResult.ok(tariffs.cardProductRegions(id));
    }

    @GetMapping("/reprice-suggestions")
    public ApiResult<PageResult<RepriceSuggestionRow>> suggestions(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNumber, @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResult.ok(suggestions.page(status, pageNumber, pageSize));
    }

    @PostMapping("/reprice-suggestions/{id}/confirm")
    public ApiResult<ActivePrice> confirm(@PathVariable long id) {
        return ApiResult.ok(prices.confirmSuggestion(id, actor.requireOperator()));
    }

    @PostMapping("/reprice-suggestions/{id}/reject")
    public ApiResult<Void> reject(@PathVariable long id) {
        prices.rejectSuggestion(id, actor.requireOperator());
        return ApiResult.ok(null);
    }

    @PostMapping("/fx/sync")
    public ApiResult<FxSyncResult> syncFx() {
        return ApiResult.ok(fxSync.run(actor.requireOperator()));
    }

    @GetMapping("/fx/rates")
    public ApiResult<List<FxRateRow>> rates(@RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(fxRates.recent(limit));
    }

    /** 全局值；SKU/供应商级覆盖不在这里改。 */
    public record PricingSettingsView(RepriceMode repriceMode, BigDecimal repriceThresholdRate, BigDecimal minMarginRate, String fxSource,
            String fxCron, Integer fxStaleHours, boolean fxStale) {
    }

    /** 只改传了的字段；先全部校验再写，任一不合法整笔不改。 */
    public record PricingSettingsUpdate(RepriceMode repriceMode, BigDecimal repriceThresholdRate, BigDecimal minMarginRate, String fxSource,
            String fxCron, Integer fxStaleHours) {
    }

    @GetMapping("/settings/pricing")
    public ApiResult<PricingSettingsView> pricingSettings() {
        return ApiResult.ok(view());
    }

    @PutMapping("/settings/pricing")
    public ApiResult<PricingSettingsView> updatePricingSettings(@RequestBody PricingSettingsUpdate body) {
        String operator = actor.requireOperator();
        requireRate("毛利率变动阈值", body.repriceThresholdRate(), false);
        requireRate("最低毛利率", body.minMarginRate(), true);
        if (body.fxSource() != null && body.fxSource().isBlank()) {
            throw new UserFacingException("汇率来源不能为空");
        }
        if (body.fxCron() != null && !CronExpression.isValidExpression(body.fxCron())) {
            throw new UserFacingException("汇率同步时间不是合法的 cron（秒 分 时 日 月 周）：" + body.fxCron());
        }
        if (body.fxStaleHours() != null && body.fxStaleHours() < 1) {
            throw new UserFacingException("汇率过期告警小时数至少为 1");
        }
        set(PricingSettings.REPRICE_MODE, body.repriceMode(), operator);
        set(PricingSettings.REPRICE_THRESHOLD_RATE, body.repriceThresholdRate(), operator);
        set(PricingSettings.MIN_MARGIN_RATE, body.minMarginRate(), operator);
        set(PricingSettings.FX_SOURCE, body.fxSource() == null ? null : body.fxSource().strip(), operator);
        set(PricingSettings.FX_CRON, body.fxCron(), operator);
        set(PricingSettings.FX_STALE_HOURS, body.fxStaleHours(), operator);
        return ApiResult.ok(view());
    }

    private static void requireRate(String label, BigDecimal value, boolean zeroAllowed) {
        if (value != null && (value.compareTo(BigDecimal.ONE) >= 0 || value.signum() < 0 || !zeroAllowed && value.signum() == 0)) {
            throw new UserFacingException(label + "须在 " + (zeroAllowed ? "0（含）" : "0") + " 与 1 之间，如 0.03 表示 3%");
        }
    }

    private <T> void set(SettingKey<T> key, T value, String operator) {
        if (value != null) {
            settings.set(key, SettingScope.GLOBAL, value, operator);
        }
    }

    private PricingSettingsView view() {
        return new PricingSettingsView(settings.get(PricingSettings.REPRICE_MODE), settings.get(PricingSettings.REPRICE_THRESHOLD_RATE),
                settings.get(PricingSettings.MIN_MARGIN_RATE), settings.get(PricingSettings.FX_SOURCE), settings.get(PricingSettings.FX_CRON),
                settings.get(PricingSettings.FX_STALE_HOURS), fxSync.isStale());
    }
}
