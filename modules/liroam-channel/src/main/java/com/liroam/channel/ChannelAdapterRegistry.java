package com.liroam.channel;

import java.util.Map;
import java.util.Optional;

import com.liroam.channel.spi.ChannelAdapter;

/** 按 {@code lr_channel.adapter_key}（= Bean 名）找渠道适配器；上层不按渠道写分支。 */
public class ChannelAdapterRegistry {

    private final Map<String, ChannelAdapter> adaptersByKey;

    public ChannelAdapterRegistry(Map<String, ChannelAdapter> adaptersByKey) {
        this.adaptersByKey = Map.copyOf(adaptersByKey);
    }

    public Optional<ChannelAdapter> find(String adapterKey) {
        return Optional.ofNullable(adaptersByKey.get(adapterKey));
    }

    ChannelAdapter require(ChannelDirectory.Channel channel) {
        return find(channel.adapterKey()).orElseThrow(() -> new IllegalStateException(
                "渠道 " + channel.code() + " 的适配器 " + channel.adapterKey() + " 未装配"));
    }
}
