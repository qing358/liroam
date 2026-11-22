package com.liroam.common.web;

/**
 * 自有接口按进程挂载：{@code liroam.web.surface=buyer} 只注册买家接口（{@code /buyer/liroam/**}），
 * {@code manager} 只注册管理接口（{@code /manager/liroam/**}）。各模块的自动配置用
 * {@code @ConditionalOnProperty(name = PROPERTY, havingValue = BUYER|MANAGER)} 注册控制器。
 */
public final class WebSurface {

    public static final String PROPERTY = "liroam.web.surface";
    public static final String BUYER = "buyer";
    public static final String MANAGER = "manager";

    private WebSurface() {
    }
}
