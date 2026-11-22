package com.liroam.channel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.liroam.common.audit.AuditLog;
import com.liroam.common.web.UserFacingException;
import com.liroam.pricing.Json;

/** 渠道（{@code lr_channel}）：启停、拉单节拍、计佣、买家可看用量；改配置留审计。渠道行由迁移 / 运维建，后台只改配置。 */
public class ChannelDirectory {

    public record Channel(long id, String code, String name, String adapterKey, boolean enabled, String pullCron, boolean buyerCanViewUsage,
            boolean commissionEnabled) {
    }

    /** @param adapterWired 本进程是否装配了该渠道的适配器；未装配不能启用 */
    public record ChannelView(long id, String code, String name, String adapterKey, boolean enabled, String pullCron, boolean buyerCanViewUsage,
            boolean commissionEnabled, boolean adapterWired) {
    }

    /** 后台改配置；为空的字段不改。 */
    public record ChannelConfig(Boolean enabled, String pullCron, Boolean buyerCanViewUsage, Boolean commissionEnabled) {
    }

    private static final String SELECT = """
            SELECT id, code, name, adapter_key, enabled, pull_cron, buyer_can_view_usage, commission_enabled FROM lr_channel
            """;

    private final JdbcClient jdbc;
    private final ChannelAdapterRegistry adapters;
    private final AuditLog audit;

    public ChannelDirectory(JdbcClient jdbc, ChannelAdapterRegistry adapters, AuditLog audit) {
        this.jdbc = jdbc;
        this.adapters = adapters;
        this.audit = audit;
    }

    public List<Channel> enabled() {
        return jdbc.sql(SELECT + " WHERE enabled = 1 ORDER BY id").query(Channel.class).list();
    }

    public Channel require(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Channel.class).optional()
                .orElseThrow(() -> new UserFacingException("找不到这个渠道"));
    }

    public List<ChannelView> list() {
        return jdbc.sql(SELECT + " ORDER BY id").query(Channel.class).list().stream().map(this::view).toList();
    }

    public ChannelView update(long id, ChannelConfig config, String actor) {
        Channel before = require(id);
        boolean enabled = config.enabled() != null ? config.enabled() : before.enabled();
        String pullCron = config.pullCron() != null ? blankToNull(config.pullCron()) : before.pullCron();
        PullSchedule.parse(pullCron);
        if (enabled && !wired(before)) {
            throw new UserFacingException("渠道 " + before.code() + " 的适配器 " + before.adapterKey() + " 未装配，不能启用");
        }
        boolean buyerCanViewUsage = config.buyerCanViewUsage() != null ? config.buyerCanViewUsage() : before.buyerCanViewUsage();
        boolean commission = config.commissionEnabled() != null ? config.commissionEnabled() : before.commissionEnabled();
        jdbc.sql("""
                UPDATE lr_channel SET enabled = ?, pull_cron = ?, buyer_can_view_usage = ?, commission_enabled = ?, version = version + 1,
                       updated_at = UTC_TIMESTAMP(3)
                 WHERE id = ?
                """).params(enabled, pullCron, buyerCanViewUsage, commission, id).update();
        Channel after = require(id);
        audit.record(actor, "CHANNEL_CONFIG", "CHANNEL", Long.toString(id), Json.write(config(before)), Json.write(config(after)));
        return view(after);
    }

    private ChannelView view(Channel c) {
        return new ChannelView(c.id(), c.code(), c.name(), c.adapterKey(), c.enabled(), c.pullCron(), c.buyerCanViewUsage(), c.commissionEnabled(),
                wired(c));
    }

    private boolean wired(Channel c) {
        return adapters.find(c.adapterKey()).isPresent();
    }

    private static Map<String, Object> config(Channel c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.enabled());
        m.put("pullCron", c.pullCron());
        m.put("buyerCanViewUsage", c.buyerCanViewUsage());
        m.put("commissionEnabled", c.commissionEnabled());
        return m;
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }
}
