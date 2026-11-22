package com.liroam.shell.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.shell.order.OrderPaidFulfillmentListener;
import com.liroam.shell.order.PaidOrderCompensationScanner;
import com.liroam.shell.order.PaidOrderFulfillment;

import cn.lili.modules.order.order.service.OrderItemService;

/** 壳挂接点 H1/H1′（domain-design §5）。worker 类任务只在 {@code liroam.worker.enabled=true} 的进程（manager-api）开。 */
@AutoConfiguration(after = LiroamFulfillmentAutoConfiguration.class)
public class LiroamShellBridgeAutoConfiguration {

    @Bean
    PaidOrderFulfillment liroamPaidOrderFulfillment(OrderItemService orderItemService, SkuBindings liroamSkuBindings,
            TopUpIntents liroamTopUpIntents, FulfillmentEntry liroamFulfillmentEntry) {
        return new PaidOrderFulfillment(orderItemService, liroamSkuBindings, liroamTopUpIntents, liroamFulfillmentEntry);
    }

    @Bean
    OrderPaidFulfillmentListener liroamOrderPaidFulfillmentListener(PaidOrderFulfillment liroamPaidOrderFulfillment) {
        return new OrderPaidFulfillmentListener(liroamPaidOrderFulfillment);
    }

    @Bean
    @ConditionalOnProperty(name = "liroam.worker.enabled", havingValue = "true")
    PaidOrderCompensationScanner liroamPaidOrderCompensationScanner(JdbcClient liroamJdbcClient, PaidOrderFulfillment liroamPaidOrderFulfillment,
            PlatformTransactionManager transactionManager, Environment env) {
        var settings = new PaidOrderCompensationScanner.Settings(
                env.getProperty("liroam.worker.paid-order-scan.lookback", Duration.class, Duration.ofHours(24)),
                ZoneId.of(env.getProperty("liroam.shell.zone", "Asia/Shanghai")),
                env.getProperty("liroam.worker.paid-order-scan.batch-size", Integer.class, 200));
        return new PaidOrderCompensationScanner(liroamJdbcClient, liroamPaidOrderFulfillment, new TransactionTemplate(transactionManager),
                settings, Clock.systemUTC());
    }
}
