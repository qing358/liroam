package com.liroam.fulfillment.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierAdapterRegistry;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.WebSurface;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRules;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.credential.CredentialTimeouts;
import com.liroam.fulfillment.delivery.ShellDeliveries;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.credential.CredentialDeliveries;
import com.liroam.fulfillment.credential.CredentialEntry;
import com.liroam.fulfillment.credential.CredentialImports;
import com.liroam.fulfillment.delivery.DeliveryProgress;
import com.liroam.fulfillment.delivery.LineProgress;
import com.liroam.fulfillment.poll.CardRefresh;
import com.liroam.fulfillment.poll.PollScheduler;
import com.liroam.fulfillment.poll.PollTiers;
import com.liroam.fulfillment.poll.UsageMirror;
import com.liroam.fulfillment.pool.CardPool;
import com.liroam.fulfillment.store.CallLogs;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardCredentials;
import com.liroam.fulfillment.store.CardPlans;
import com.liroam.fulfillment.store.Offerings;
import com.liroam.fulfillment.store.PoolItems;
import com.liroam.fulfillment.store.FulfillmentLines;
import com.liroam.fulfillment.store.ShellDeliveryQueue;
import com.liroam.fulfillment.store.SupplierCommands;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.fulfillment.worker.CommandExecutor;
import com.liroam.fulfillment.web.BuyerCardController;
import com.liroam.fulfillment.web.ManagerCardController;
import com.liroam.fulfillment.worker.FulfillmentSchedules;
import com.liroam.fulfillment.worker.FulfillmentWorker;
import com.liroam.fulfillment.worker.SupplierRateLimiter;

@AutoConfiguration(after = LiroamCatalogAutoConfiguration.class)
public class LiroamFulfillmentAutoConfiguration {

    /** 测试注入可拨的时钟；进程里没有 {@link Clock} Bean 时用系统 UTC。 */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock liroamFulfillmentClock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionTemplate liroamFulfillmentTx(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    CardAssets liroamCardAssets(JdbcClient liroamJdbcClient) {
        return new CardAssets(liroamJdbcClient);
    }

    @Bean
    CardPlans liroamCardPlans(JdbcClient liroamJdbcClient) {
        return new CardPlans(liroamJdbcClient);
    }

    @Bean
    FulfillmentLines liroamFulfillmentLines(JdbcClient liroamJdbcClient) {
        return new FulfillmentLines(liroamJdbcClient);
    }

    @Bean
    SupplierCommands liroamSupplierCommands(JdbcClient liroamJdbcClient) {
        return new SupplierCommands(liroamJdbcClient);
    }

    @Bean
    ManualTasks liroamManualTasks(JdbcClient liroamJdbcClient, AuditLog liroamAuditLog) {
        return new ManualTasks(liroamJdbcClient, liroamAuditLog);
    }

    @Bean
    FulfillmentRules liroamFulfillmentRules(SkuBindings liroamSkuBindings, SupplierDirectory liroamSupplierDirectory, CardAssets liroamCardAssets) {
        return new FulfillmentRules(liroamSkuBindings, liroamSupplierDirectory, liroamCardAssets);
    }

    @Bean
    TopUpIntents liroamTopUpIntents(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient, FulfillmentRules liroamFulfillmentRules,
            Clock liroamFulfillmentClock) {
        return new TopUpIntents(liroamFulfillmentTx, liroamJdbcClient, liroamFulfillmentRules, liroamFulfillmentClock);
    }

    @Bean
    FulfillmentEntry liroamFulfillmentEntry(FulfillmentRules liroamFulfillmentRules, FulfillmentLines liroamFulfillmentLines) {
        return new FulfillmentEntry(liroamFulfillmentRules, liroamFulfillmentLines);
    }

    /** 端口实现（H5）由集成方写在 shell-bridge；没有实现的进程只积压待回写。 */
    @Bean
    ShellDeliveries liroamShellDeliveries(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient,
            ObjectProvider<ShellOrderDelivery> port, Settings liroamSettings, Clock liroamFulfillmentClock) {
        return new ShellDeliveries(liroamFulfillmentTx, new ShellDeliveryQueue(liroamJdbcClient), Optional.ofNullable(port.getIfAvailable()),
                liroamSettings, liroamFulfillmentClock);
    }

    @Bean
    CredentialTimeouts liroamCredentialTimeouts(JdbcClient liroamJdbcClient, Settings liroamSettings, ManualTasks liroamManualTasks,
            Clock liroamFulfillmentClock) {
        return new CredentialTimeouts(liroamJdbcClient, liroamSettings, liroamManualTasks, liroamFulfillmentClock);
    }

    @Bean
    PoolItems liroamPoolItems(JdbcClient liroamJdbcClient) {
        return new PoolItems(liroamJdbcClient);
    }

    @Bean
    CardCredentials liroamCardCredentials(JdbcClient liroamJdbcClient) {
        return new CardCredentials(liroamJdbcClient);
    }

    @Bean
    CardPool liroamCardPool(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient, PoolItems liroamPoolItems,
            SensitiveCipher liroamSensitiveCipher, AuditLog liroamAuditLog) {
        return new CardPool(liroamFulfillmentTx, liroamJdbcClient, liroamPoolItems, liroamSensitiveCipher, liroamAuditLog);
    }

    @Bean
    PollTiers liroamPollTiers(CardAssets liroamCardAssets, Settings liroamSettings) {
        return new PollTiers(liroamCardAssets, liroamSettings);
    }

    @Bean
    UsageMirror liroamUsageMirror(CardAssets liroamCardAssets, CardPlans liroamCardPlans, PollTiers liroamPollTiers) {
        return new UsageMirror(liroamCardAssets, liroamCardPlans, liroamPollTiers);
    }

    @Bean
    PollScheduler liroamPollScheduler(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient, CardAssets liroamCardAssets,
            SupplierCommands liroamSupplierCommands, PollTiers liroamPollTiers, Settings liroamSettings, Clock liroamFulfillmentClock) {
        return new PollScheduler(liroamFulfillmentTx, liroamJdbcClient, liroamCardAssets, liroamSupplierCommands, liroamPollTiers, liroamSettings,
                liroamFulfillmentClock);
    }

    @Bean
    CardRefresh liroamCardRefresh(TransactionTemplate liroamFulfillmentTx, CardAssets liroamCardAssets, SupplierCommands liroamSupplierCommands,
            Settings liroamSettings, Clock liroamFulfillmentClock) {
        return new CardRefresh(liroamFulfillmentTx, liroamCardAssets, liroamSupplierCommands, liroamSettings, liroamFulfillmentClock);
    }

    @Bean
    CredentialDeliveries liroamCredentialDeliveries(CardCredentials liroamCardCredentials, SensitiveCipher liroamSensitiveCipher,
            ApplicationEventPublisher events, Clock liroamFulfillmentClock) {
        return new CredentialDeliveries(liroamCardCredentials, liroamSensitiveCipher, events, liroamFulfillmentClock);
    }

    @Bean
    CredentialImports liroamCredentialImports(TransactionTemplate liroamFulfillmentTx, CardAssets liroamCardAssets, PoolItems liroamPoolItems,
            CredentialDeliveries liroamCredentialDeliveries, SensitiveCipher liroamSensitiveCipher, AuditLog liroamAuditLog) {
        return new CredentialImports(liroamFulfillmentTx, liroamCardAssets, liroamPoolItems, liroamCredentialDeliveries, liroamSensitiveCipher,
                liroamAuditLog);
    }

    @Bean
    CredentialEntry liroamCredentialEntry(TransactionTemplate liroamFulfillmentTx, CardAssets liroamCardAssets,
            CredentialDeliveries liroamCredentialDeliveries, AuditLog liroamAuditLog) {
        return new CredentialEntry(liroamFulfillmentTx, liroamCardAssets, liroamCredentialDeliveries, liroamAuditLog);
    }

    @Bean
    LineProgress liroamLineProgress(FulfillmentLines liroamFulfillmentLines, CardAssets liroamCardAssets, CardPlans liroamCardPlans,
            ShellDeliveries liroamShellDeliveries) {
        return new LineProgress(liroamFulfillmentLines, liroamCardAssets, liroamCardPlans, liroamShellDeliveries);
    }

    @Bean
    DeliveryProgress liroamDeliveryProgress(CardAssets liroamCardAssets, FulfillmentLines liroamFulfillmentLines, LineProgress liroamLineProgress,
            PollTiers liroamPollTiers, Clock liroamFulfillmentClock) {
        return new DeliveryProgress(liroamCardAssets, liroamFulfillmentLines, liroamLineProgress, liroamPollTiers, liroamFulfillmentClock);
    }

    @Bean
    SupplierRateLimiter liroamSupplierRateLimiter(DataSource dataSource, Settings liroamSettings, Clock liroamFulfillmentClock) {
        return new SupplierRateLimiter(dataSource, liroamSettings, liroamFulfillmentClock);
    }

    @Bean
    CommandExecutor liroamCommandExecutor(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient, SupplierCommands liroamSupplierCommands,
            CardAssets liroamCardAssets, CardPlans liroamCardPlans, SupplierDirectory liroamSupplierDirectory,
            SupplierAdapterRegistry liroamSupplierAdapterRegistry, SupplierRateLimiter liroamSupplierRateLimiter, CardPool liroamCardPool,
            CredentialDeliveries liroamCredentialDeliveries, LineProgress liroamLineProgress, UsageMirror liroamUsageMirror,
            PollTiers liroamPollTiers, ManualTasks liroamManualTasks, Settings liroamSettings, SensitiveCipher liroamSensitiveCipher,
            Clock liroamFulfillmentClock) {
        return new CommandExecutor(liroamFulfillmentTx, liroamSupplierCommands, liroamCardAssets, liroamCardPlans, new Offerings(liroamJdbcClient),
                liroamSupplierDirectory, liroamSupplierAdapterRegistry, liroamSupplierRateLimiter, liroamCardPool, liroamCredentialDeliveries,
                liroamLineProgress, liroamUsageMirror, liroamPollTiers, liroamManualTasks, new CallLogs(liroamJdbcClient), liroamSettings,
                liroamSensitiveCipher, liroamFulfillmentClock);
    }

    @Bean
    CardQueries liroamCardQueries(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient, CardAssets liroamCardAssets,
            CardCredentials liroamCardCredentials, SupplierCommands liroamSupplierCommands, FulfillmentRules liroamFulfillmentRules,
            SensitiveCipher liroamSensitiveCipher) {
        return new CardQueries(liroamFulfillmentTx, liroamJdbcClient, liroamCardAssets, liroamCardCredentials, liroamSupplierCommands,
                liroamFulfillmentRules, liroamSensitiveCipher);
    }

    @Bean
    CardActions liroamCardActions(TransactionTemplate liroamFulfillmentTx, CardAssets liroamCardAssets, CardPlans liroamCardPlans,
            FulfillmentLines liroamFulfillmentLines, SupplierCommands liroamSupplierCommands, SupplierDirectory liroamSupplierDirectory,
            SupplierAdapterRegistry liroamSupplierAdapterRegistry, CardPool liroamCardPool, ManualTasks liroamManualTasks, Settings liroamSettings,
            AuditLog liroamAuditLog, Clock liroamFulfillmentClock) {
        return new CardActions(liroamFulfillmentTx, liroamCardAssets, liroamCardPlans, liroamFulfillmentLines, liroamSupplierCommands,
                liroamSupplierDirectory,
                liroamSupplierAdapterRegistry, liroamCardPool, liroamManualTasks, liroamSettings, liroamAuditLog, liroamFulfillmentClock);
    }

    @Bean
    FulfillmentWorker liroamFulfillmentWorker(TransactionTemplate liroamFulfillmentTx, FulfillmentLines liroamFulfillmentLines,
            CardAssets liroamCardAssets, CardPlans liroamCardPlans, SupplierCommands liroamSupplierCommands, SkuBindings liroamSkuBindings,
            SupplierDirectory liroamSupplierDirectory, SupplierAdapterRegistry liroamSupplierAdapterRegistry, Settings liroamSettings,
            ManualTasks liroamManualTasks, CredentialTimeouts liroamCredentialTimeouts, ShellDeliveries liroamShellDeliveries,
            Clock liroamFulfillmentClock) {
        return new FulfillmentWorker(liroamFulfillmentTx, liroamFulfillmentLines, liroamCardAssets, liroamCardPlans, liroamSupplierCommands,
                liroamSkuBindings, liroamSupplierDirectory, liroamSupplierAdapterRegistry, liroamSettings, liroamManualTasks,
                liroamCredentialTimeouts, liroamShellDeliveries, liroamFulfillmentClock);
    }

    /** 拆卡、指令执行、轮询排队的定时节拍；宿主进程须开启 {@code @EnableScheduling}。 */
    @Bean
    @ConditionalOnProperty(name = "liroam.worker.enabled", havingValue = "true")
    FulfillmentSchedules liroamFulfillmentSchedules(FulfillmentWorker liroamFulfillmentWorker, CommandExecutor liroamCommandExecutor,
            PollScheduler liroamPollScheduler, Environment env) {
        return new FulfillmentSchedules(liroamFulfillmentWorker, liroamCommandExecutor, liroamPollScheduler,
                env.getProperty("liroam.worker.batch-size", Integer.class, 50), workerId());
    }

    private static String workerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        String id = host + ":" + ProcessHandle.current().pid();
        return id.length() <= 64 ? id : id.substring(id.length() - 64);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.BUYER)
    static class BuyerWeb {

        @Bean
        BuyerCardController liroamBuyerCardController(CurrentActor actor, CardQueries liroamCardQueries, CardRefresh liroamCardRefresh,
                TopUpIntents liroamTopUpIntents) {
            return new BuyerCardController(actor, liroamCardQueries, liroamCardRefresh, liroamTopUpIntents);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    static class ManagerWeb {

        @Bean
        ManagerCardController liroamManagerCardController(CurrentActor actor, CardQueries liroamCardQueries, CredentialEntry liroamCredentialEntry,
                CredentialImports liroamCredentialImports, CardPool liroamCardPool, ManualTasks liroamManualTasks) {
            return new ManagerCardController(actor, liroamCardQueries, liroamCredentialEntry, liroamCredentialImports, liroamCardPool,
                    liroamManualTasks);
        }
    }
}
