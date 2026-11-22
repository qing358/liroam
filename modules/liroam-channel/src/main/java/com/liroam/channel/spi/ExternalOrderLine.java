package com.liroam.channel.spi;

/**
 * 渠道订单的一行。
 *
 * @param lineNo        单内行号，同一单内唯一且稳定（重复拉单时不变）
 * @param externalSkuId 渠道 SKU，按 {@code lr_channel_sku_mapping} 映射到本站 SKU
 * @param paidFen       该行实付合计（人民币分），作为订单行快照的售价
 */
public record ExternalOrderLine(String lineNo, String externalSkuId, int qty, long paidFen) {

    public ExternalOrderLine {
        if (qty <= 0) {
            throw new IllegalArgumentException("渠道订单行数量必须大于 0");
        }
    }
}
