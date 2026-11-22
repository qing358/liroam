package com.liroam.channel.config;

import java.time.Clock;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.channel.ChannelAdapterRegistry;
import com.liroam.channel.ChannelClaims;
import com.liroam.channel.ChannelDirectory;
import com.liroam.channel.ChannelOrders;
import com.liroam.channel.ChannelReconciliation;
import com.liroam.channel.ChannelSchedules;
import com.liroam.channel.ChannelSkuMappings;
import com.liroam.channel.ChannelWiringCheck;
import com.liroam.channel.ChannelWriteBacks;
import com.liroam.channel.MemberPhones;
import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.channel.web.BuyerChannelOrderController;
import com.liroam.channel.web.ManagerChannelController;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.WebSurface;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.card.CardQueries;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.pricing.config.LiroamPricingAutoConfiguration;
import com.liroam.pricing.snapshot.OrderLineSnapshots;

@AutoConfiguration(after = {LiroamFulfillmentAutoConfiguration.class, LiroamPricingAutoConfiguration.class})
public class LiroamChannelAutoConfiguration {

    /** 允许一个适配器都没有（渠道资质未到）；启用的渠道缺适配器由 {@link ChannelWiringCheck} 拦。 */
    @Bean
    ChannelAdapterRegistry liroamChannelAdapterRegistry(ListableBeanFactory beans) {
        return new ChannelAdapterRegistry(beans.getBeansOfType(ChannelAdapter.class));
    }

    @Bean
    ChannelDirectory liroamChannelDirectory(JdbcClient liroamJdbcClient, ChannelAdapterRegistry liroamChannelAdapterRegistry, AuditLog liroamAuditLog) {
        return new ChannelDirectory(liroamJdbcClient, liroamChannelAdapterRegistry, liroamAuditLog);
    }

    @Bean
    ChannelWiringCheck liroamChannelWiringCheck(ChannelDirectory liroamChannelDirectory, ChannelAdapterRegistry liroamChannelAdapterRegistry) {
        return new ChannelWiringCheck(liroamChannelDirectory, liroamChannelAdapterRegistry);
    }

    @Bean
    ChannelSkuMappings liroamChannelSkuMappings(JdbcClient liroamJdbcClient, ChannelDirectory liroamChannelDirectory, SkuBindings liroamSkuBindings,
            AuditLog liroamAuditLog) {
        return new ChannelSkuMappings(liroamJdbcClient, liroamChannelDirectory, liroamSkuBindings, liroamAuditLog);
    }

    @Bean
    ChannelOrders liroamChannelOrders(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient, ChannelDirectory liroamChannelDirectory,
            ChannelAdapterRegistry liroamChannelAdapterRegistry, ChannelSkuMappings liroamChannelSkuMappings, SkuBindings liroamSkuBindings,
            FulfillmentEntry liroamFulfillmentEntry, OrderLineSnapshots liroamOrderLineSnapshots, SensitiveCipher liroamSensitiveCipher,
            ManualTasks liroamManualTasks, AuditLog liroamAuditLog, Clock liroamFulfillmentClock) {
        return new ChannelOrders(liroamFulfillmentTx, liroamJdbcClient, liroamChannelDirectory, liroamChannelAdapterRegistry, liroamChannelSkuMappings,
                liroamSkuBindings, liroamFulfillmentEntry, liroamOrderLineSnapshots, liroamSensitiveCipher, liroamManualTasks, liroamAuditLog,
                liroamFulfillmentClock);
    }

    @Bean
    ChannelWriteBacks liroamChannelWriteBacks(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient,
            ChannelDirectory liroamChannelDirectory, ChannelAdapterRegistry liroamChannelAdapterRegistry, CardQueries liroamCardQueries,
            Settings liroamSettings, ManualTasks liroamManualTasks, AuditLog liroamAuditLog, Clock liroamFulfillmentClock) {
        return new ChannelWriteBacks(liroamFulfillmentTx, liroamJdbcClient, liroamChannelDirectory, liroamChannelAdapterRegistry, liroamCardQueries,
                liroamSettings, liroamManualTasks, liroamAuditLog, liroamFulfillmentClock);
    }

    @Bean
    ChannelReconciliation liroamChannelReconciliation(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient,
            ChannelDirectory liroamChannelDirectory, ChannelAdapterRegistry liroamChannelAdapterRegistry, ManualTasks liroamManualTasks,
            AuditLog liroamAuditLog, Clock liroamFulfillmentClock) {
        return new ChannelReconciliation(liroamFulfillmentTx, liroamJdbcClient, liroamChannelDirectory, liroamChannelAdapterRegistry,
                liroamManualTasks, liroamAuditLog, liroamFulfillmentClock);
    }

    /** 会员手机号端口由壳桥实现；没有实现的进程（如纯 worker）认领一律提示先绑定手机号。 */
    @Bean
    ChannelClaims liroamChannelClaims(TransactionTemplate liroamFulfillmentTx, JdbcClient liroamJdbcClient, ObjectProvider<MemberPhones> memberPhones,
            SensitiveCipher liroamSensitiveCipher, CardActions liroamCardActions, AuditLog liroamAuditLog, Clock liroamFulfillmentClock) {
        return new ChannelClaims(liroamFulfillmentTx, liroamJdbcClient, memberPhones, liroamSensitiveCipher, liroamCardActions, liroamAuditLog,
                liroamFulfillmentClock);
    }

    /** 拉单、回传、日对账节拍；宿主进程须开启 {@code @EnableScheduling}。 */
    @Bean
    @ConditionalOnProperty(name = "liroam.worker.enabled", havingValue = "true")
    ChannelSchedules liroamChannelSchedules(ChannelOrders liroamChannelOrders, ChannelWriteBacks liroamChannelWriteBacks,
            ChannelReconciliation liroamChannelReconciliation, Environment env) {
        return new ChannelSchedules(liroamChannelOrders, liroamChannelWriteBacks, liroamChannelReconciliation,
                env.getProperty("liroam.worker.batch-size", Integer.class, 50));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.BUYER)
    static class BuyerWeb {

        @Bean
        BuyerChannelOrderController liroamBuyerChannelOrderController(CurrentActor actor, ChannelClaims liroamChannelClaims) {
            return new BuyerChannelOrderController(actor, liroamChannelClaims);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    static class ManagerWeb {

        @Bean
        ManagerChannelController liroamManagerChannelController(CurrentActor actor, ChannelDirectory liroamChannelDirectory,
                ChannelSkuMappings liroamChannelSkuMappings, ChannelOrders liroamChannelOrders, ChannelWriteBacks liroamChannelWriteBacks,
                ChannelReconciliation liroamChannelReconciliation) {
            return new ManagerChannelController(actor, liroamChannelDirectory, liroamChannelSkuMappings, liroamChannelOrders, liroamChannelWriteBacks,
                    liroamChannelReconciliation);
        }
    }
}
