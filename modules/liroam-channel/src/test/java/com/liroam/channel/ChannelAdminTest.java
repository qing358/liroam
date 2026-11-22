package com.liroam.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.channel.ChannelDirectory.ChannelConfig;
import com.liroam.channel.ChannelDirectory.ChannelView;
import com.liroam.channel.ChannelOrders.OrderFilter;
import com.liroam.channel.ChannelSkuMappings.MappingView;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.channel.spi.ExternalStatementLine;
import com.liroam.channel.testing.ChannelFixture;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.testing.MutableClock;

/** 后台：渠道配置（启停、拉单间隔、计佣、买家看用量，留审计）、SKU 映射增删改、渠道订单列表（回传与对账标记）。 */
class ChannelAdminTest {

    private static ChannelFixture f;
    private static ChannelDirectory channels;
    private static ChannelSkuMappings mappings;
    private static ChannelOrders orders;

    @BeforeAll
    static void start() {
        f = ChannelFixture.start("channel_admin");
        channels = f.bean(ChannelDirectory.class);
        mappings = f.bean(ChannelSkuMappings.class);
        orders = f.bean(ChannelOrders.class);
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    @Test
    void configChangesAreValidatedAndAudited() {
        ChannelView updated = channels.update(f.channelId, new ChannelConfig(false, "10m", false, true), "op-1");

        assertThat(updated).extracting(ChannelView::code, ChannelView::enabled, ChannelView::pullCron, ChannelView::buyerCanViewUsage,
                ChannelView::commissionEnabled, ChannelView::adapterWired).containsExactly("CH_A", false, "10m", false, true, true);
        assertThat(f.h.jdbc.queryForObject("SELECT CONCAT(actor, '|', CAST(before_json AS CHAR), '|', CAST(after_json AS CHAR)) FROM lr_audit_log "
                + "WHERE action = 'CHANNEL_CONFIG'", String.class))
                .startsWith("op-1|").contains("\"enabled\": true").contains("\"enabled\": false").contains("\"pullCron\": \"10m\"");

        assertThatThrownBy(() -> channels.update(f.channelId, new ChannelConfig(true, "0m", false, false), "op"))
                .isInstanceOf(UserFacingException.class).hasMessageContaining("拉单间隔");
        long orphan = f.insertChannel("CH_X", "not-wired", false, false);
        assertThat(channels.list()).filteredOn(c -> c.id() == orphan).singleElement().extracting(ChannelView::adapterWired).isEqualTo(false);
        assertThatThrownBy(() -> channels.update(orphan, new ChannelConfig(true, "5m", false, false), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("渠道 CH_X 的适配器 not-wired 未装配，不能启用");
        assertThatThrownBy(() -> channels.update(999_999, new ChannelConfig(true, "5m", false, false), "op"))
                .isInstanceOf(UserFacingException.class).hasMessage("找不到这个渠道");
    }

    @Test
    void skuMappingsAreValidatedAgainstTravelCardSkusAndAudited() {
        MappingView created = mappings.create(f.channelId, " EXT-C ", "SKU-B", "op");

        assertThat(created).extracting(MappingView::externalSkuId, MappingView::liGoodsSkuId, MappingView::enabled).containsExactly("EXT-C", "SKU-B", true);
        assertThat(mappings.list(f.channelId)).extracting(MappingView::externalSkuId).containsExactly("EXT-A", "EXT-B", "EXT-C");
        assertThatThrownBy(() -> mappings.create(f.channelId, "EXT-C", "SKU-A", "op")).isInstanceOf(UserFacingException.class)
                .hasMessage("渠道 SKU EXT-C 已有映射");
        assertThatThrownBy(() -> mappings.create(f.channelId, "EXT-D", "SKU-A-TOP", "op")).isInstanceOf(UserFacingException.class)
                .hasMessage("本站 SKU SKU-A-TOP 不是旅行卡新卡商品");
        assertThatThrownBy(() -> mappings.create(f.channelId, "EXT-D", "NOPE", "op")).isInstanceOf(UserFacingException.class)
                .hasMessage("本站 SKU NOPE 不是旅行卡新卡商品");

        MappingView changed = mappings.update(created.id(), "SKU-A", false, "op");
        assertThat(changed).extracting(MappingView::liGoodsSkuId, MappingView::enabled).containsExactly("SKU-A", false);
        mappings.delete(created.id(), "op");
        assertThat(mappings.list(f.channelId)).extracting(MappingView::externalSkuId).containsExactly("EXT-A", "EXT-B");
        assertThat(f.h.jdbc.queryForList("SELECT action FROM lr_audit_log WHERE target_type = 'CHANNEL_SKU_MAPPING' ORDER BY id", String.class))
                .containsExactly("CHANNEL_SKU_MAPPING_CREATE", "CHANNEL_SKU_MAPPING_UPDATE", "CHANNEL_SKU_MAPPING_DELETE");
    }

    @Test
    void disabledMappingCountsAsUnmapped() {
        f.h.jdbc.update("UPDATE lr_channel_sku_mapping SET enabled = 0 WHERE external_sku_id = 'EXT-A'");
        f.adapter.addOrder("O-1", MutableClock.START, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));

        orders.pullNow(f.channelId, "op");

        assertThat(f.orderStatus("O-1")).isEqualTo("MANUAL");
    }

    @Test
    void orderPageShowsLinesWithWriteBackAndReconMarkers() {
        f.h.importPoolA(1, true);
        f.adapter.addOrder("O-1", MutableClock.START, "13800138000", new ExternalOrderLine("1", "EXT-A", 1, 4900));
        f.adapter.addOrder("O-2", MutableClock.START, "13800138000", new ExternalOrderLine("1", "EXT-X", 1, 4900));
        orders.pullNow(f.channelId, "op");
        f.h.drain();
        f.bean(ChannelWriteBacks.class).runOnce(10);
        f.adapter.statement(LocalDate.of(2026, 11, 22), new ExternalStatementLine("O-1", 4900, "{}"), new ExternalStatementLine("O-2", 4900, "{}"));
        f.bean(ChannelReconciliation.class).run(f.channelId, LocalDate.of(2026, 11, 22), "op");

        PageResult<ChannelOrderView> page = orders.page(new OrderFilter(f.channelId, null, null, 1, 10));

        assertThat(page.total()).isEqualTo(2);
        ChannelOrderView manual = page.records().get(0);
        ChannelOrderView done = page.records().get(1);
        assertThat(done).extracting(ChannelOrderView::externalOrderId, ChannelOrderView::channelCode, ChannelOrderView::status,
                ChannelOrderView::statusText, ChannelOrderView::paidFen, ChannelOrderView::reconStatus, ChannelOrderView::reconText,
                ChannelOrderView::claimed, ChannelOrderView::orderedText)
                .containsExactly("O-1", "CH_A", "DONE", "已完成", 4900L, "BALANCED", "已对平", false, "2026-11-22 12:00");
        assertThat(done.lines()).singleElement().satisfies(l -> {
            assertThat(l.externalSkuId()).isEqualTo("EXT-A");
            assertThat(l.liGoodsSkuId()).isEqualTo("SKU-A");
            assertThat(l.fulfillmentStatus()).isEqualTo("COMPLETED");
            assertThat(l.writebackStatus()).isEqualTo("DONE");
            assertThat(l.writebackText()).isEqualTo("已回传");
        });
        assertThat(manual).extracting(ChannelOrderView::externalOrderId, ChannelOrderView::statusText, ChannelOrderView::reconText)
                .containsExactly("O-2", "待人工处理", "有差异");
        assertThat(manual.lastError()).contains("EXT-X");
        assertThat(manual.lines().getFirst().fulfillmentStatus()).isNull();

        assertThat(orders.page(new OrderFilter(null, "MANUAL", null, 1, 10)).records()).extracting(ChannelOrderView::externalOrderId)
                .containsExactly("O-2");
        assertThat(orders.page(new OrderFilter(null, null, "O-1", 1, 10)).records()).extracting(ChannelOrderView::externalOrderId)
                .containsExactly("O-1");
        assertThatThrownBy(() -> orders.page(new OrderFilter(null, "WHAT", null, 1, 10))).isInstanceOf(UserFacingException.class);
    }
}
