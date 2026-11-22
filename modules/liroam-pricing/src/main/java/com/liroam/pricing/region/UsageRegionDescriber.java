package com.liroam.pricing.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.common.web.UserFacingException;

/**
 * SKU 详情页「使用区域说明」的唯一生成器（§7.6）：区域与实名、限制说明全部来自资费表导出的可用区域
 * （{@code lr_card_product_region}）与卡产品级限制；单区域与区域组同一套文案。下单时 {@code fullText} 原文进快照。
 */
public class UsageRegionDescriber {

    private final JdbcClient jdbc;
    private final SkuBindings bindings;

    public UsageRegionDescriber(JdbcClient jdbc, SkuBindings bindings) {
        this.jdbc = jdbc;
        this.bindings = bindings;
    }

    public UsageRegionView describe(long skuBindingId) {
        return describe(bindings.findById(skuBindingId).orElseThrow(() -> new UserFacingException("SKU 绑定 #" + skuBindingId + " 不存在")));
    }

    /** 壳 SKU 未绑定（普通商品）为空。 */
    public Optional<UsageRegionView> describeShellSku(String shellSkuId) {
        return bindings.findByShellSkuId(shellSkuId).map(this::describe);
    }

    private record RegionRow(String code, String name, String continent, Boolean kycRequired, String note) {

        UsageRegionView.Region view() {
            return new UsageRegionView.Region(code, name, continent, Boolean.TRUE.equals(kycRequired), note);
        }
    }

    private UsageRegionView describe(SkuBinding sku) {
        String scope = sku.regionId() != null ? "r.id = ?" : "r.id IN (SELECT region_id FROM lr_region_group_member WHERE group_id = ?)";
        List<UsageRegionView.Region> regions = jdbc.sql("""
                SELECT r.code, r.name_zh AS name, r.continent, cpr.kyc_required, cpr.restriction_note AS note
                  FROM lr_usage_region r
                  LEFT JOIN lr_card_product_region cpr ON cpr.region_id = r.id AND cpr.card_product_id = ? AND cpr.active = 1
                 WHERE """ + " " + scope + " ORDER BY r.code")
                .params(sku.cardProductId(), sku.regionId() != null ? sku.regionId() : sku.regionGroupId())
                .query(RegionRow.class).list().stream().map(RegionRow::view).toList();
        List<String> productNotes = jdbc.sql("""
                SELECT t.note FROM lr_card_product p,
                       JSON_TABLE(p.constraints_json, '$.notes[*]' COLUMNS (note TEXT PATH '$')) t
                 WHERE p.id = ?
                """).param(sku.cardProductId()).query(String.class).list();
        return compose(regions, productNotes);
    }

    static UsageRegionView compose(List<UsageRegionView.Region> regions, List<String> productNotes) {
        List<String> names = regions.stream().map(UsageRegionView.Region::name).toList();
        List<String> kycNames = regions.stream().filter(UsageRegionView.Region::kycRequired).map(UsageRegionView.Region::name).toList();
        String joined = String.join("、", names);

        StringBuilder headline = new StringBuilder("适用于").append(joined);
        if (names.size() > 1) {
            headline.append("（共 ").append(names.size()).append(" 个国家/地区）");
        }
        if (!kycNames.isEmpty()) {
            headline.append("；").append(String.join("、", kycNames)).append("需实名认证");
        }

        List<String> lines = new ArrayList<>();
        lines.add("适用国家/地区：" + joined);
        lines.add(kycNames.isEmpty() ? "实名要求：无需实名认证" : "需实名认证：" + String.join("、", kycNames));
        for (UsageRegionView.Region r : regions) {
            if (r.note() != null && !r.note().isBlank()) {
                lines.add("【" + r.name() + "】" + r.note());
            }
        }
        for (String note : productNotes) {
            lines.add("【使用限制】" + note);
        }
        return new UsageRegionView(headline.toString(), regions, String.join("\n", lines));
    }
}
