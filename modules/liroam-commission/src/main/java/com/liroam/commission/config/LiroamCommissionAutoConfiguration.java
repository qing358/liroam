package com.liroam.commission.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.commission.AgentDirectory;
import com.liroam.commission.CommissionAccruals;
import com.liroam.commission.CommissionAlerts;
import com.liroam.commission.CommissionLedger;
import com.liroam.commission.CommissionPolicies;
import com.liroam.commission.CommissionSchedules;
import com.liroam.commission.CommissionStrategy;
import com.liroam.commission.Withdrawals;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.WebSurface;
import com.liroam.commission.web.BuyerAgentController;
import com.liroam.commission.web.ManagerCommissionController;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.refund.config.LiroamRefundAutoConfiguration;

/**
 * 佣金模块装配。代理目录 {@link AgentDirectory} 由 shell-bridge 提供（读壳分销员）；没有时整个模块不装配，退款冲正回落到只记审计。
 * 本配置排在退款之前，以便账本取代退款模块的默认冲正实现。
 */
@AutoConfiguration(after = LiroamFulfillmentAutoConfiguration.class, before = LiroamRefundAutoConfiguration.class)
@ConditionalOnBean(AgentDirectory.class)
public class LiroamCommissionAutoConfiguration {

    @Bean
    CommissionStrategy.PaidRate liroamCommissionPaidRate() {
        return new CommissionStrategy.PaidRate();
    }

    @Bean
    CommissionStrategy.FloorDiff liroamCommissionFloorDiff(CommissionPolicies liroamCommissionPolicies) {
        return new CommissionStrategy.FloorDiff(liroamCommissionPolicies);
    }

    @Bean
    CommissionStrategy.None liroamCommissionNone() {
        return new CommissionStrategy.None();
    }

    @Bean
    CommissionPolicies liroamCommissionPolicies(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient,
            ObjectProvider<CommissionStrategy> strategies, AuditLog liroamAuditLog, Clock liroamFulfillmentClock) {
        return new CommissionPolicies(new TransactionTemplate(transactionManager), liroamJdbcClient, () -> strategies.orderedStream().toList(),
                liroamAuditLog, liroamFulfillmentClock);
    }

    @Bean
    CommissionLedger liroamCommissionLedger(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient, AgentDirectory agents,
            AuditLog liroamAuditLog, Clock liroamFulfillmentClock) {
        return new CommissionLedger(new TransactionTemplate(transactionManager), liroamJdbcClient, agents, liroamAuditLog, liroamFulfillmentClock);
    }

    @Bean
    CommissionAccruals liroamCommissionAccruals(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient,
            CommissionPolicies liroamCommissionPolicies, ObjectProvider<CommissionStrategy> strategies, AgentDirectory agents,
            CommissionLedger liroamCommissionLedger, Environment env, Clock liroamFulfillmentClock) {
        return new CommissionAccruals(new TransactionTemplate(transactionManager), liroamJdbcClient, liroamCommissionPolicies,
                () -> strategies.orderedStream().toList(), agents, liroamCommissionLedger,
                ZoneId.of(env.getProperty("liroam.shell.zone", "Asia/Shanghai")), liroamFulfillmentClock);
    }

    @Bean
    Withdrawals liroamWithdrawals(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient, AgentDirectory agents,
            CommissionLedger liroamCommissionLedger, CommissionPolicies liroamCommissionPolicies, SensitiveCipher liroamSensitiveCipher,
            Settings liroamSettings, AuditLog liroamAuditLog, Clock liroamFulfillmentClock) {
        return new Withdrawals(new TransactionTemplate(transactionManager), liroamJdbcClient, agents, liroamCommissionLedger, liroamCommissionPolicies,
                liroamSensitiveCipher, liroamSettings, liroamAuditLog, liroamFulfillmentClock);
    }

    @Bean
    CommissionAlerts liroamCommissionAlerts(JdbcClient liroamJdbcClient, Settings liroamSettings) {
        return new CommissionAlerts(liroamJdbcClient, liroamSettings);
    }

    @Bean
    @ConditionalOnProperty(name = "liroam.worker.enabled", havingValue = "true")
    CommissionSchedules liroamCommissionSchedules(CommissionAccruals liroamCommissionAccruals, CommissionLedger liroamCommissionLedger,
            Environment env) {
        return new CommissionSchedules(liroamCommissionAccruals, liroamCommissionLedger, env.getProperty("liroam.worker.batch-size", Integer.class, 50));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.BUYER)
    static class BuyerWeb {

        @Bean
        BuyerAgentController liroamBuyerAgentController(CurrentActor actor, AgentDirectory agents, CommissionLedger liroamCommissionLedger,
                Withdrawals liroamWithdrawals) {
            return new BuyerAgentController(actor, agents, liroamCommissionLedger, liroamWithdrawals);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    static class ManagerWeb {

        @Bean
        ManagerCommissionController liroamManagerCommissionController(CurrentActor actor, CommissionPolicies liroamCommissionPolicies,
                CommissionLedger liroamCommissionLedger, Withdrawals liroamWithdrawals) {
            return new ManagerCommissionController(actor, liroamCommissionPolicies, liroamCommissionLedger, liroamWithdrawals);
        }
    }
}
