package com.liroam.pricing.price;

import java.math.BigDecimal;
import java.util.List;

/** 批量改价预览；执行成功时原样返回执行所用的同一份结果。行顺序同请求（去重后）。 */
public record BatchPreview(List<Row> rows, int belowMinCount) {

    public record Row(long skuBindingId, String liGoodsSkuId, long currentPriceFen, long costCnyFen, long newPriceFen, BigDecimal marginAfter,
            BigDecimal minMarginRate, boolean belowMin) {
    }
}
