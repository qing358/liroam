package com.liroam.refund.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.WebSurface;
import com.liroam.fulfillment.card.CardActions;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.fulfillment.poll.CardRefresh;
import com.liroam.refund.AuditOnlyCommissionReversal;
import com.liroam.refund.CommissionReversal;
import com.liroam.refund.RefundCases;
import com.liroam.refund.RefundRules;
import com.liroam.refund.web.BuyerRefundController;
import com.liroam.refund.web.ManagerRefundController;

@AutoConfiguration(after = LiroamFulfillmentAutoConfiguration.class)
public class LiroamRefundAutoConfiguration {

    @Bean
    RefundRules liroamRefundRules(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient, AuditLog liroamAuditLog) {
        return new RefundRules(new TransactionTemplate(transactionManager), liroamJdbcClient, liroamAuditLog);
    }

    /** 佣金模块（P5）提供自己的实现后，这个只记审计的默认实现即不装配。 */
    @Bean
    @ConditionalOnMissingBean(CommissionReversal.class)
    CommissionReversal liroamAuditOnlyCommissionReversal(AuditLog liroamAuditLog) {
        return new AuditOnlyCommissionReversal(liroamAuditLog);
    }

    @Bean
    RefundCases liroamRefundCases(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient, RefundRules liroamRefundRules,
            CardActions liroamCardActions, CardRefresh liroamCardRefresh, CommissionReversal commissionReversal, Settings liroamSettings,
            AuditLog liroamAuditLog, Clock clock) {
        return new RefundCases(new TransactionTemplate(transactionManager), liroamJdbcClient, liroamRefundRules, liroamCardActions,
                liroamCardRefresh, commissionReversal, liroamSettings, liroamAuditLog, clock);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.BUYER)
    static class BuyerWeb {

        @Bean
        BuyerRefundController liroamBuyerRefundController(CurrentActor actor, RefundCases liroamRefundCases) {
            return new BuyerRefundController(actor, liroamRefundCases);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    static class ManagerWeb {

        @Bean
        ManagerRefundController liroamManagerRefundController(CurrentActor actor, RefundCases liroamRefundCases, RefundRules liroamRefundRules) {
            return new ManagerRefundController(actor, liroamRefundCases, liroamRefundRules);
        }
    }
}
