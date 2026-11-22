package com.liroam.pricing.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.WebSurface;
import com.liroam.pricing.catalog.CatalogAdmin;
import com.liroam.pricing.catalog.ShellSkuDirectory;
import com.liroam.pricing.fx.FxRateSource;
import com.liroam.pricing.fx.FxRates;
import com.liroam.pricing.fx.FxSync;
import com.liroam.pricing.fx.FxSyncJob;
import com.liroam.pricing.ops.PricingAlerts;
import com.liroam.pricing.price.CostCalculator;
import com.liroam.pricing.price.CostVersions;
import com.liroam.pricing.price.PriceVersions;
import com.liroam.pricing.price.RepriceSuggestions;
import com.liroam.pricing.price.Repricing;
import com.liroam.pricing.price.ShellPriceWriter;
import com.liroam.pricing.region.RegionDictionary;
import com.liroam.pricing.region.UsageRegionDescriber;
import com.liroam.pricing.snapshot.OrderLineSnapshots;
import com.liroam.pricing.tariff.TariffImports;
import com.liroam.pricing.web.CatalogManagerController;
import com.liroam.pricing.web.PricingBuyerController;
import com.liroam.pricing.web.PricingManagerController;

/**
 * 定价与旅行卡商品配置。{@link ShellPriceWriter}（H7）、{@link ShellSkuDirectory} 与 {@link CurrentActor} 由 {@code liroam-shell-bridge} 提供；
 * 汇率来源按 {@link FxRateSource} Bean 收集（mock 在 {@code ..pricing.mock..}，只 dev/test 装配）。
 */
@AutoConfiguration(after = LiroamCatalogAutoConfiguration.class)
public class LiroamPricingAutoConfiguration {

    @Bean
    TransactionTemplate liroamPricingTx(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    RegionDictionary liroamRegionDictionary() {
        return RegionDictionary.load();
    }

    @Bean
    FxRates liroamFxRates(JdbcClient liroamJdbcClient) {
        return new FxRates(liroamJdbcClient);
    }

    @Bean
    CostVersions liroamCostVersions(JdbcClient liroamJdbcClient) {
        return new CostVersions(liroamJdbcClient);
    }

    @Bean
    CostCalculator liroamCostCalculator(JdbcClient liroamJdbcClient, SkuBindings liroamSkuBindings, FxRates liroamFxRates) {
        return new CostCalculator(liroamJdbcClient, liroamSkuBindings, liroamFxRates);
    }

    @Bean
    PriceVersions liroamPriceVersions(JdbcClient liroamJdbcClient, TransactionTemplate liroamPricingTx, ShellPriceWriter shellPriceWriter,
            SkuBindings liroamSkuBindings, CostCalculator liroamCostCalculator, CostVersions liroamCostVersions, AuditLog liroamAuditLog) {
        return new PriceVersions(liroamJdbcClient, liroamPricingTx, shellPriceWriter, liroamSkuBindings, liroamCostCalculator, liroamCostVersions,
                liroamAuditLog);
    }

    @Bean
    RepriceSuggestions liroamRepriceSuggestions(JdbcClient liroamJdbcClient) {
        return new RepriceSuggestions(liroamJdbcClient);
    }

    @Bean
    Repricing liroamRepricing(JdbcClient liroamJdbcClient, TransactionTemplate liroamPricingTx, Settings liroamSettings, SkuBindings liroamSkuBindings,
            CostCalculator liroamCostCalculator, CostVersions liroamCostVersions, PriceVersions liroamPriceVersions, AuditLog liroamAuditLog) {
        return new Repricing(liroamJdbcClient, liroamPricingTx, liroamSettings, liroamSkuBindings, liroamCostCalculator, liroamCostVersions,
                liroamPriceVersions, liroamAuditLog);
    }

    @Bean
    TariffImports liroamTariffImports(JdbcClient liroamJdbcClient, TransactionTemplate liroamPricingTx, Settings liroamSettings,
            AuditLog liroamAuditLog, SupplierDirectory liroamSupplierDirectory, RegionDictionary liroamRegionDictionary, Repricing liroamRepricing) {
        return new TariffImports(liroamJdbcClient, liroamPricingTx, liroamSettings, liroamAuditLog, liroamSupplierDirectory, liroamRegionDictionary,
                liroamRepricing);
    }

    @Bean
    FxSync liroamFxSync(TransactionTemplate liroamPricingTx, Settings liroamSettings, AuditLog liroamAuditLog, ObjectProvider<FxRateSource> sources,
            FxRates liroamFxRates, Repricing liroamRepricing) {
        return new FxSync(liroamPricingTx, liroamSettings, liroamAuditLog, () -> sources.orderedStream().toList(), liroamFxRates, liroamRepricing,
                Clock.systemUTC());
    }

    @Bean
    UsageRegionDescriber liroamUsageRegionDescriber(JdbcClient liroamJdbcClient, SkuBindings liroamSkuBindings) {
        return new UsageRegionDescriber(liroamJdbcClient, liroamSkuBindings);
    }

    @Bean
    OrderLineSnapshots liroamOrderLineSnapshots(JdbcClient liroamJdbcClient, SkuBindings liroamSkuBindings, UsageRegionDescriber liroamUsageRegionDescriber,
            FxRates liroamFxRates, CostVersions liroamCostVersions, PriceVersions liroamPriceVersions) {
        return new OrderLineSnapshots(liroamJdbcClient, liroamSkuBindings, liroamUsageRegionDescriber, liroamFxRates, liroamCostVersions,
                liroamPriceVersions);
    }

    /** {@code fx.cron} 按壳时区解释。 */
    @Bean
    @ConditionalOnProperty(name = "liroam.worker.enabled", havingValue = "true")
    FxSyncJob liroamFxSyncJob(FxSync liroamFxSync, Settings liroamSettings, Environment env) {
        return new FxSyncJob(liroamFxSync, liroamSettings, ZoneId.of(env.getProperty("liroam.shell.zone", "Asia/Shanghai")));
    }

    @Bean
    PricingAlerts liroamPricingAlerts(FxSync liroamFxSync, JdbcClient liroamJdbcClient, Settings liroamSettings) {
        return new PricingAlerts(liroamFxSync, liroamJdbcClient, liroamSettings);
    }

    @Bean
    CatalogAdmin liroamCatalogAdmin(JdbcClient liroamJdbcClient, TransactionTemplate liroamPricingTx, SkuBindings liroamSkuBindings,
            PriceVersions liroamPriceVersions, ShellSkuDirectory shellSkuDirectory, AuditLog liroamAuditLog) {
        return new CatalogAdmin(liroamJdbcClient, liroamPricingTx, liroamSkuBindings, liroamPriceVersions, shellSkuDirectory, liroamAuditLog);
    }

    @Bean
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    CatalogManagerController liroamCatalogManagerController(CurrentActor currentActor, CatalogAdmin liroamCatalogAdmin) {
        return new CatalogManagerController(currentActor, liroamCatalogAdmin);
    }

    @Bean
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.BUYER)
    PricingBuyerController liroamPricingBuyerController(UsageRegionDescriber liroamUsageRegionDescriber) {
        return new PricingBuyerController(liroamUsageRegionDescriber);
    }

    @Bean
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    PricingManagerController liroamPricingManagerController(CurrentActor currentActor, TariffImports liroamTariffImports,
            PriceVersions liroamPriceVersions, RepriceSuggestions liroamRepriceSuggestions, Settings liroamSettings, FxSync liroamFxSync,
            FxRates liroamFxRates) {
        return new PricingManagerController(currentActor, liroamTariffImports, liroamPriceVersions, liroamRepriceSuggestions, liroamSettings,
                liroamFxSync, liroamFxRates);
    }
}
