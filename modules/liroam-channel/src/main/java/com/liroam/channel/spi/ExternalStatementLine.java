package com.liroam.channel.spi;

/**
 * 渠道日结算单的一行（按订单）。
 *
 * @param paidFen 渠道记的买家实付（人民币分）
 * @param rawJson 渠道原始行
 */
public record ExternalStatementLine(String externalOrderId, long paidFen, String rawJson) {
}
