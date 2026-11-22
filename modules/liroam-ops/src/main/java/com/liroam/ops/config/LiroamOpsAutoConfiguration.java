package com.liroam.ops.config;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.setting.Settings;
import com.liroam.common.web.CurrentActor;
import com.liroam.common.web.UserFacingException;
import com.liroam.common.web.WebSurface;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.ops.OpsSchedules;
import com.liroam.ops.notify.ExpiryReminders;
import com.liroam.ops.notify.MemberSms;
import com.liroam.ops.notify.Notifications;
import com.liroam.ops.recon.PaymentReconciliation;
import com.liroam.ops.recon.ReconAlerts;
import com.liroam.ops.recon.ReconRuns;
import com.liroam.ops.recon.ShellPayments;
import com.liroam.ops.recon.SupplierReconciliation;
import com.liroam.ops.report.OpsReports;
import com.liroam.ops.web.BuyerNotificationController;
import com.liroam.ops.web.ManagerOpsController;

/** 运营模块装配。壳支付数据 {@link ShellPayments} 由 shell-bridge 提供；短信 {@link MemberSms} 可缺省（只发站内通知）。 */
@AutoConfiguration(after = LiroamFulfillmentAutoConfiguration.class)
public class LiroamOpsAutoConfiguration {

    private static ZoneId zone(Environment env) {
        return ZoneId.of(env.getProperty("liroam.shell.zone", "Asia/Shanghai"));
    }

    @Bean
    ExpiryReminders liroamExpiryReminders(JdbcClient liroamJdbcClient, Settings liroamSettings, ObjectProvider<MemberSms> sms,
            Clock liroamFulfillmentClock) {
        return new ExpiryReminders(liroamJdbcClient, liroamSettings, Optional.ofNullable(sms.getIfAvailable()), liroamFulfillmentClock);
    }

    @Bean
    Notifications liroamNotifications(JdbcClient liroamJdbcClient, Clock liroamFulfillmentClock) {
        return new Notifications(liroamJdbcClient, liroamFulfillmentClock);
    }

    @Bean
    ReconRuns liroamReconRuns(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient, AuditLog liroamAuditLog,
            Clock liroamFulfillmentClock) {
        return new ReconRuns(new TransactionTemplate(transactionManager), liroamJdbcClient, liroamAuditLog, liroamFulfillmentClock);
    }

    @Bean
    PaymentReconciliation liroamPaymentReconciliation(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient,
            ObjectProvider<ShellPayments> shell, ReconRuns liroamReconRuns, Settings liroamSettings) {
        ShellPayments payments = day -> {
            ShellPayments port = shell.getIfAvailable();
            if (port == null) {
                throw new UserFacingException("当前进程读不到壳订单，无法做支付对账");
            }
            return port.paidOrders(day);
        };
        return new PaymentReconciliation(new TransactionTemplate(transactionManager), liroamJdbcClient, payments, liroamReconRuns, liroamSettings);
    }

    @Bean
    SupplierReconciliation liroamSupplierReconciliation(PlatformTransactionManager transactionManager, JdbcClient liroamJdbcClient,
            SensitiveCipher liroamSensitiveCipher, ReconRuns liroamReconRuns, Environment env) {
        return new SupplierReconciliation(new TransactionTemplate(transactionManager), liroamJdbcClient, liroamSensitiveCipher, liroamReconRuns,
                zone(env));
    }

    @Bean
    OpsReports liroamOpsReports(JdbcClient liroamJdbcClient, Environment env) {
        return new OpsReports(liroamJdbcClient, zone(env));
    }

    @Bean
    ReconAlerts liroamReconAlerts(JdbcClient liroamJdbcClient, Settings liroamSettings, Environment env) {
        return new ReconAlerts(liroamJdbcClient, liroamSettings, zone(env));
    }

    @Bean
    @ConditionalOnProperty(name = "liroam.worker.enabled", havingValue = "true")
    OpsSchedules liroamOpsSchedules(ExpiryReminders liroamExpiryReminders) {
        return new OpsSchedules(liroamExpiryReminders);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.BUYER)
    static class BuyerWeb {

        @Bean
        BuyerNotificationController liroamBuyerNotificationController(CurrentActor actor, Notifications liroamNotifications) {
            return new BuyerNotificationController(actor, liroamNotifications);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = WebSurface.PROPERTY, havingValue = WebSurface.MANAGER)
    static class ManagerWeb {

        @Bean
        ManagerOpsController liroamManagerOpsController(CurrentActor actor, OpsReports liroamOpsReports,
                PaymentReconciliation liroamPaymentReconciliation, SupplierReconciliation liroamSupplierReconciliation, ReconRuns liroamReconRuns) {
            return new ManagerOpsController(actor, liroamOpsReports, liroamPaymentReconciliation, liroamSupplierReconciliation, liroamReconRuns);
        }
    }
}
