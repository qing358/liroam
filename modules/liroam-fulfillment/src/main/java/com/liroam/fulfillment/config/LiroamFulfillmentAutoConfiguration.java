package com.liroam.fulfillment.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.SkuBindings;
import com.liroam.catalog.SupplierDirectory;
import com.liroam.catalog.config.LiroamCatalogAutoConfiguration;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRules;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.FulfillmentLines;

@AutoConfiguration(after = LiroamCatalogAutoConfiguration.class)
public class LiroamFulfillmentAutoConfiguration {

    @Bean
    CardAssets liroamCardAssets(JdbcClient liroamJdbcClient) {
        return new CardAssets(liroamJdbcClient);
    }

    @Bean
    FulfillmentLines liroamFulfillmentLines(JdbcClient liroamJdbcClient) {
        return new FulfillmentLines(liroamJdbcClient);
    }

    @Bean
    FulfillmentRules liroamFulfillmentRules(SkuBindings liroamSkuBindings, SupplierDirectory liroamSupplierDirectory, CardAssets liroamCardAssets) {
        return new FulfillmentRules(liroamSkuBindings, liroamSupplierDirectory, liroamCardAssets);
    }

    @Bean
    FulfillmentEntry liroamFulfillmentEntry(FulfillmentRules liroamFulfillmentRules, FulfillmentLines liroamFulfillmentLines) {
        return new FulfillmentEntry(liroamFulfillmentRules, liroamFulfillmentLines);
    }
}
