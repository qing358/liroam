package com.liroam.channel;

import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 启动时核对：每个启用的渠道都有对应适配器 Bean，否则启动失败（同供应商 {@code SupplierWiringCheck}）。
 * 生产环境装了 {@code @Simulated} 适配器由 {@code ProductionSafetyGate} 拦截，这里不重复判断。
 */
public class ChannelWiringCheck implements SmartInitializingSingleton {

    private final ChannelDirectory channels;
    private final ChannelAdapterRegistry adapters;

    public ChannelWiringCheck(ChannelDirectory channels, ChannelAdapterRegistry adapters) {
        this.channels = channels;
        this.adapters = adapters;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> missing = channels.enabled().stream()
                .filter(c -> adapters.find(c.adapterKey()).isEmpty())
                .map(c -> c.code() + "→" + c.adapterKey())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("启用的渠道缺少适配器：" + missing + "；装配适配器或在 lr_channel 停用该渠道");
        }
    }
}
