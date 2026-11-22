package com.liroam.channel.testing;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.channel.config.LiroamChannelAutoConfiguration;
import com.liroam.channel.spi.ChannelAdapter;
import com.liroam.fulfillment.testing.FulfillmentHarness;
import com.liroam.fulfillment.testing.TestCatalog;
import com.liroam.pricing.catalog.ShellSkuDirectory;
import com.liroam.pricing.config.LiroamPricingAutoConfiguration;
import com.liroam.pricing.price.ShellPriceWriter;

/**
 * 渠道集成测试装配：履约两形状假上游（{@link FulfillmentHarness}）+ 定价 + 渠道自动配置 + 脚本化渠道 {@value #ADAPTER} + 假会员手机号。
 * 每个用例前 {@link #reset()}：清台账与渠道表，重建渠道 {@value #CODE}（启用、买家可看用量）与两条映射 {@code EXT-A → SKU-A}、{@code EXT-B → SKU-B}。
 */
public final class ChannelFixture implements AutoCloseable {

    public static final String ADAPTER = "fake-channel";
    public static final String CODE = "CH_A";

    private static final List<String> CHANNEL_TABLES = List.of("lr_channel_order_line", "lr_channel_order", "lr_channel_sku_mapping", "lr_channel",
            "lr_order_line_snapshot", "lr_recon_diff", "lr_recon_run");

    public final FulfillmentHarness h;
    public final TestCatalog c;
    public final FakeChannelAdapter adapter;
    public final FakeMemberPhones phones;
    public long channelId;

    private ChannelFixture(FulfillmentHarness h, FakeChannelAdapter adapter, FakeMemberPhones phones) {
        this.h = h;
        this.c = h.catalog;
        this.adapter = adapter;
        this.phones = phones;
    }

    public static ChannelFixture start(String module) {
        return start(module, Map.of(), ctx -> { });
    }

    /** @param extras 追加 Bean（如另一个渠道适配器）或自动配置 */
    public static ChannelFixture start(String module, Map<String, Object> properties, Consumer<AnnotationConfigApplicationContext> extras) {
        FakeChannelAdapter adapter = new FakeChannelAdapter();
        FakeMemberPhones phones = new FakeMemberPhones();
        FulfillmentHarness h = FulfillmentHarness.start(module, properties, ctx -> {
            ctx.registerBean(ADAPTER, ChannelAdapter.class, () -> adapter);
            ctx.registerBean(FakeMemberPhones.class, () -> phones);
            ctx.registerBean(ShellPriceWriter.class, () -> (sku, fen) -> { });
            ctx.registerBean(ShellSkuDirectory.class, () -> sku -> Optional.empty());
            ctx.register(LiroamPricingAutoConfiguration.class, LiroamChannelAutoConfiguration.class);
            extras.accept(ctx);
        });
        ChannelFixture f = new ChannelFixture(h, adapter, phones);
        f.reset();
        return f;
    }

    public <T> T bean(Class<T> type) {
        return h.bean(type);
    }

    public void reset() {
        h.reset();
        CHANNEL_TABLES.forEach(t -> h.jdbc.update("DELETE FROM " + t));
        adapter.reset();
        phones.reset();
        channelId = insertChannel(CODE, ADAPTER, true, true);
        mapSku("EXT-A", "SKU-A");
        mapSku("EXT-B", "SKU-B");
    }

    public long insertChannel(String code, String adapterKey, boolean enabled, boolean buyerCanViewUsage) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        h.jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO lr_channel (code, name, adapter_key, enabled, pull_cron, buyer_can_view_usage) VALUES (?, ?, ?, ?, '5m', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, "渠道" + code);
            ps.setString(3, adapterKey);
            ps.setBoolean(4, enabled);
            ps.setBoolean(5, buyerCanViewUsage);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void mapSku(String externalSkuId, String shellSkuId) {
        h.jdbc.update("INSERT INTO lr_channel_sku_mapping (channel_id, external_sku_id, li_goods_sku_id) VALUES (?, ?, ?)", channelId, externalSkuId,
                shellSkuId);
    }

    public long orderId(String externalOrderId) {
        return h.jdbc.queryForObject("SELECT id FROM lr_channel_order WHERE channel_id = ? AND external_order_id = ?", Long.class, channelId,
                externalOrderId);
    }

    public String orderStatus(String externalOrderId) {
        return h.jdbc.queryForObject("SELECT status FROM lr_channel_order WHERE channel_id = ? AND external_order_id = ?", String.class, channelId,
                externalOrderId);
    }

    public List<Long> cardsOf(String externalOrderId) {
        return h.jdbc.queryForList("""
                SELECT c.id FROM lr_card_asset c JOIN lr_fulfillment_line l ON l.id = c.fulfillment_line_id
                 WHERE l.source_type = 'CHANNEL' AND l.source_ref = ? ORDER BY l.id, c.unit_seq
                """, Long.class, externalOrderId);
    }

    @Override
    public void close() {
        h.close();
    }
}
