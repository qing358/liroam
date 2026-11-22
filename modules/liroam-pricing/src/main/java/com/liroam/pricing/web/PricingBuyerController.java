package com.liroam.pricing.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.web.ApiResult;
import com.liroam.pricing.region.UsageRegionDescriber;
import com.liroam.pricing.region.UsageRegionView;

/** 买家端（{@code /buyer/goods/**} 公开，不需登录）。 */
@RestController
public class PricingBuyerController {

    private final UsageRegionDescriber describer;

    public PricingBuyerController(UsageRegionDescriber describer) {
        this.describer = describer;
    }

    /** 商品详情页使用区域说明；普通商品（未绑定）返回 {@code result: null}。 */
    @GetMapping("/buyer/goods/liroam/usage-region")
    public ApiResult<UsageRegionView> usageRegion(@RequestParam String skuId) {
        return ApiResult.ok(describer.describeShellSku(skuId).orElse(null));
    }
}
