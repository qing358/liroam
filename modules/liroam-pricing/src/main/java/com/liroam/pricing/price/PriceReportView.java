package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.util.List;

/** 调价报表：表头为当前 ACTIVE 汇率（从未同步为空）与是否过期，行按 SKU 绑定 id 升序。 */
public record PriceReportView(BigDecimal fxRate, String fxFetchedText, boolean fxStale, List<PriceReportRow> rows) {
}
