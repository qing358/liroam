package com.liroam.pricing.price;

/**
 * 一轮成本重算的计数。
 *
 * @param skipped 算不出成本（如资费表无该区域）的 SKU 数
 * @param failed  其它异常（如 AUTO 写回壳失败）的 SKU 数，下次触发再算
 */
public record RepriceRun(int costVersions, int suggestions, int autoActivated, int skipped, int failed) {
}
