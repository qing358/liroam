package com.liroam.shell.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBindings;
import com.liroam.common.web.CurrentActor;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRules;
import com.liroam.fulfillment.ShellOrderDelivery;
import com.liroam.fulfillment.TopUpIntents;
import com.liroam.fulfillment.config.LiroamFulfillmentAutoConfiguration;
import com.liroam.fulfillment.delivery.CardShipments;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.pricing.config.LiroamPricingAutoConfiguration;
import com.liroam.pricing.catalog.ShellSkuDirectory;
import com.liroam.pricing.price.ShellPriceWriter;
import com.liroam.pricing.snapshot.OrderLineSnapshots;
import com.liroam.shell.goods.ShellGoodsSkus;
import com.liroam.shell.goods.ShellSkuPriceWriter;
import com.liroam.shell.order.ShellShipping;
import com.liroam.shell.order.OrderPaidFulfillmentListener;
import com.liroam.shell.order.PaidOrderCompensationScanner;
import com.liroam.shell.order.PaidOrderFulfillment;
import com.liroam.shell.order.TradeIntoDbListener;
import com.liroam.shell.order.TradeOrderPreparation;
import com.liroam.shell.security.ShellCurrentActor;

import cn.lili.cache.Cache;
import cn.lili.modules.goods.service.GoodsService;
import cn.lili.modules.goods.service.GoodsSkuService;
import cn.lili.modules.order.order.service.OrderItemService;
import cn.lili.modules.order.order.service.OrderService;

/**
 * 壳挂接点（domain-design §5）：H1/H1′ 支付收单与补偿扫描、H2 交易落库校验与快照、H5 发货回写、H7 售价写回，以及操作人端口。
 * worker 类任务只在 {@code liroam.worker.enabled=true} 的进程（manager-api）开。
 */
@AutoConfiguration(after = { LiroamFulfillmentAutoConfiguration.class, LiroamPricingAutoConfiguration.class })
public class LiroamShellBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CurrentActor.class)
    CurrentActor liroamShellCurrentActor() {
        return new ShellCurrentActor();
    }

    @Bean
    ShellPriceWriter liroamShellSkuPriceWriter(GoodsSkuService goodsSkuService, GoodsService goodsService, Cache<?> cache) {
        return new ShellSkuPriceWriter(goodsSkuService, goodsService, cache);
    }

    @Bean
    ShellSkuDirectory liroamShellGoodsSkus(GoodsSkuService goodsSkuService) {
        return new ShellGoodsSkus(goodsSkuService);
    }

    @Bean
    ShellOrderDelivery liroamShellShipping(OrderService orderService, OrderItemService orderItemService, SkuBindings liroamSkuBindings,
            CardShipments liroamCardShipments, ManualTasks liroamManualTasks) {
        return new ShellShipping(orderService, orderItemService, liroamSkuBindings, liroamCardShipments, liroamManualTasks);
    }

    @Bean
    TradeIntoDbListener liroamTradeIntoDbListener(OrderItemService orderItemService, SkuBindings liroamSkuBindings,
            FulfillmentRules liroamFulfillmentRules, TopUpIntents liroamTopUpIntents, OrderLineSnapshots liroamOrderLineSnapshots) {
        return new TradeIntoDbListener(new TradeOrderPreparation(orderItemService, liroamSkuBindings, liroamFulfillmentRules, liroamTopUpIntents,
                liroamOrderLineSnapshots));
    }

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
