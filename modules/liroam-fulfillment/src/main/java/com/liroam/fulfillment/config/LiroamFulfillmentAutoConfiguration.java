package com.liroam.fulfillment.config;

import java.time.Clock;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierAdapterRegistry;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRules;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.credential.CredentialTimeouts;
import com.liroam.fulfillment.delivery.ShellDeliveries;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardPlans;
import com.liroam.fulfillment.store.FulfillmentLines;
import com.liroam.fulfillment.store.ShellDeliveryQueue;
import com.liroam.fulfillment.store.SupplierCommands;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.fulfillment.worker.FulfillmentWorker;

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
    TopUpIntents liroamTopUpIntents(JdbcClient liroamJdbcClient) {
        return new TopUpIntents(liroamJdbcClient);
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
    FulfillmentWorker liroamFulfillmentWorker(TransactionTemplate liroamFulfillmentTx, FulfillmentLines liroamFulfillmentLines,
            CardAssets liroamCardAssets, CardPlans liroamCardPlans, SupplierCommands liroamSupplierCommands, SkuBindings liroamSkuBindings,
            SupplierDirectory liroamSupplierDirectory, SupplierAdapterRegistry liroamSupplierAdapterRegistry, Settings liroamSettings,
            ManualTasks liroamManualTasks, CredentialTimeouts liroamCredentialTimeouts, ShellDeliveries liroamShellDeliveries,
            Clock liroamFulfillmentClock) {
        return new FulfillmentWorker(liroamFulfillmentTx, liroamFulfillmentLines, liroamCardAssets, liroamCardPlans, liroamSupplierCommands,
                liroamSkuBindings, liroamSupplierDirectory, liroamSupplierAdapterRegistry, liroamSettings, liroamManualTasks,
                liroamCredentialTimeouts, liroamShellDeliveries, liroamFulfillmentClock);
    }
}
