package com.liroam.pricing.region;

import java.util.List;

/**
 * SKU 详情页「使用区域说明」。{@code fullText} 是下单快照存的原文。
 *
 * @param headline 一句话摘要，如「适用于日本、香港（共 2 个国家/地区）；香港需实名认证」
 */
public record UsageRegionView(String headline, List<Region> regions, String fullText) {

    public UsageRegionView {
        regions = List.copyOf(regions);
    }

    /** @param note 资费表原文汇总（行备注 + 相关注意事项），无则为 null */
    public record Region(String code, String name, String continent, boolean kycRequired, String note) {
    }
}
