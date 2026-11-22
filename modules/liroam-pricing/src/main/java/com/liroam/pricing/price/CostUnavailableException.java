package com.liroam.pricing.price;

import com.liroam.common.web.UserFacingException;

/** 这个 SKU 现在算不出成本（汇率未同步、资费表没有该区域、不限流量套餐…），消息说明缺什么。 */
public class CostUnavailableException extends UserFacingException {

    private static final long serialVersionUID = 1L;

    public CostUnavailableException(String message) {
        super(message);
    }
}
