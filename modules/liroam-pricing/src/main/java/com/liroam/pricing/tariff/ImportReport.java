package com.liroam.pricing.tariff;

import java.util.List;
import java.util.Map;

/**
 * 导入预览 / 激活结果。
 *
 * @param linesByKind        DATA / VOICE / SMS / CARD_FEE 行数
 * @param unmatchedCountries 区域字典里没有的国家原文（行已原样入库，不进可用区域）
 */
public record ImportReport(long sheetId, long supplierId, String fileName, String status, String effectiveFromText, int lineCount,
        Map<String, Integer> linesByKind, List<String> unmatchedCountries, List<ResourceSummary> resources) {

    /** @param regionCount 流量表里匹配上的国家/地区数 */
    public record ResourceSummary(String resourceCode, int regionCount) {
    }
}
