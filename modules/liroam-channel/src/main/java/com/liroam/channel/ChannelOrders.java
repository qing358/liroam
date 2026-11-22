package com.liroam.channel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import com.liroam.catalog.SkuBinding;
import com.liroam.catalog.SkuBindings;
import com.liroam.channel.ChannelDirectory.Channel;
import com.liroam.channel.spi.ExternalOrder;
import com.liroam.channel.spi.ExternalOrderLine;
import com.liroam.common.audit.AuditLog;
import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.fulfillment.FulfillmentEntry;
import com.liroam.fulfillment.FulfillmentRejected;
import com.liroam.fulfillment.FulfillmentRequest;
import com.liroam.fulfillment.task.ManualTasks;
import com.liroam.pricing.snapshot.OrderLineSnapshots;
import com.liroam.pricing.snapshot.SnapshotRequest;

/**
 * 渠道入单（domain-design §9.4、§11）：按节拍或后台「立即拉单」调适配器拉单，按渠道 + 外部单号幂等落 {@code lr_channel_order(_line)}；
 * 每行按 SKU 映射进唯一履约入口（不建壳订单）并记订单行快照（售价 = 渠道实付）。整单要么全部进履约，要么整单转人工：
 * 有 SKU 未映射 → {@code UNMAPPED_CHANNEL_SKU}；映射到的 SKU 不可收单 → {@code CHANNEL_ORDER_REJECTED}；处理后在后台重放。
 */
public class ChannelOrders {

    private static final Logger log = LoggerFactory.getLogger(ChannelOrders.class);

    /** 增量拉单从上次拉单开始时刻往回多看一段，兜住渠道侧延迟落库的单；重复的单按外部单号幂等。 */
    public static final Duration PULL_OVERLAP = Duration.ofMinutes(10);
    /** 渠道第一次拉单往回看多久。 */
    static final Duration FIRST_LOOKBACK = Duration.ofDays(1);
    private static final String CURSOR_KEY = "channel.pull_cursor";
    static final String TASK_REF = "CHANNEL_ORDER";

    enum Status { RECEIVED, FULFILLING, DONE, MANUAL }

    public record PullResult(int pulled, int created, int manual) {
    }

    public record OrderFilter(Long channelId, String status, String keyword, int pageNumber, int pageSize) {
    }

    private record OrderRow(long id, long channelId, String externalOrderId, String status) {
    }

    private record LineRow(long id, String externalLineNo, String externalSkuId, int qty, long paidFen) {
    }

    private record ViewRow(long id, long channelId, String channelCode, String externalOrderId, String status, long paidFen, LocalDateTime orderedAt,
            String reconStatus, String claimedMemberId, LocalDateTime claimedAt, String lastError, LocalDateTime createdAt) {
    }

    private record ViewLineRow(long channelOrderId, long id, String externalLineNo, String externalSkuId, String liGoodsSkuId, int qty, long paidFen,
            String fulfillmentStatus, String writebackStatus, int writebackAttempts, String writebackError) {
    }

    /** 整单不能进履约：有 SKU 未映射。 */
    private static final class Unmapped extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Unmapped(List<String> externalSkuIds) {
            super("渠道 SKU 未映射：" + String.join("、", externalSkuIds));
        }
    }

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final ChannelDirectory channels;
    private final ChannelAdapterRegistry adapters;
    private final ChannelSkuMappings mappings;
    private final SkuBindings bindings;
    private final FulfillmentEntry entry;
    private final OrderLineSnapshots snapshots;
    private final SensitiveCipher cipher;
    private final ManualTasks tasks;
    private final AuditLog audit;
    private final Clock clock;

    public ChannelOrders(TransactionTemplate tx, JdbcClient jdbc, ChannelDirectory channels, ChannelAdapterRegistry adapters, ChannelSkuMappings mappings,
            SkuBindings bindings, FulfillmentEntry entry, OrderLineSnapshots snapshots, SensitiveCipher cipher, ManualTasks tasks, AuditLog audit,
            Clock clock) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.channels = channels;
        this.adapters = adapters;
        this.mappings = mappings;
        this.bindings = bindings;
        this.entry = entry;
        this.snapshots = snapshots;
        this.cipher = cipher;
        this.tasks = tasks;
        this.audit = audit;
        this.clock = clock;
    }

    /** 后台「立即拉单」：不看节拍，游标照常推进。 */
    public PullResult pullNow(long channelId, String actor) {
        Channel channel = channels.require(channelId);
        if (!channel.enabled()) {
            throw new UserFacingException("渠道 " + channel.code() + " 已停用，不能拉单");
        }
        PullResult result = pull(channel);
        audit.record(actor, "CHANNEL_PULL", "CHANNEL", Long.toString(channelId), null,
                "{\"pulled\":" + result.pulled() + ",\"created\":" + result.created() + ",\"manual\":" + result.manual() + "}");
        return result;
    }

    /** worker 节拍：每个启用渠道按自己的 {@code pull_cron} 判断是否到点。 @return 本轮拉了几个渠道 */
    public int pullDue() {
        Instant now = clock.instant();
        int pulled = 0;
        for (Channel channel : channels.enabled()) {
            try {
                Optional<Instant> last = cursor(channel.id());
                if (last.isPresent() && now.isBefore(PullSchedule.parse(channel.pullCron()).nextAfter(last.get()))) {
                    continue;
                }
                pull(channel);
                pulled++;
            } catch (RuntimeException e) {
                log.error("渠道 {} 拉单失败，下轮重试", channel.code(), e);
            }
        }
        return pulled;
    }

    /** 重放待人工处理的渠道单（补映射 / SKU 恢复可售后）。 */
    public ChannelOrderView replay(long orderId, String actor) {
        try {
            tx.executeWithoutResult(status -> {
                OrderRow order = lock(orderId);
                if (!Status.MANUAL.name().equals(order.status())) {
                    throw new UserFacingException("只有待人工处理的渠道单可以重放");
                }
                admit(order);
                List<Long> open = jdbc.sql("""
                        SELECT id FROM lr_manual_task WHERE ref_type = ? AND ref_id = ? AND status = 'OPEN' AND kind IN (?, ?)
                        """).params(TASK_REF, Long.toString(orderId), ManualTasks.Kind.UNMAPPED_CHANNEL_SKU.name(),
                        ManualTasks.Kind.CHANNEL_ORDER_REJECTED.name()).query(Long.class).list();
                open.forEach(id -> tasks.resolve(id, "渠道单已重放", actor));
                audit.record(actor, "CHANNEL_ORDER_REPLAY", TASK_REF, Long.toString(orderId), null, null);
            });
        } catch (Unmapped e) {
            throw new UserFacingException(e.getMessage());
        } catch (FulfillmentRejected e) {
            throw new UserFacingException(e.reason().userMessage());
        }
        return view(orderId);
    }

    public PageResult<ChannelOrderView> page(OrderFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (filter.channelId() != null) {
            where.append(" AND o.channel_id = ?");
            args.add(filter.channelId());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            String status = filter.status().trim();
            if (Arrays.stream(Status.values()).noneMatch(s -> s.name().equals(status))) {
                throw new UserFacingException("不认识的渠道单状态：" + status);
            }
            where.append(" AND o.status = ?");
            args.add(status);
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            where.append(" AND o.external_order_id = ?");
            args.add(filter.keyword().trim());
        }
        int size = PageResult.clampSize(filter.pageSize());
        int current = Math.max(filter.pageNumber(), 1);
        long total = jdbc.sql("SELECT COUNT(*) FROM lr_channel_order o" + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(PageResult.offset(current, size));
        List<ViewRow> rows = jdbc.sql(VIEW_SELECT + where + " ORDER BY o.id DESC LIMIT ? OFFSET ?").params(pageArgs).query(ViewRow.class).list();
        return new PageResult<>(views(rows), total, current, size);
    }

    ChannelOrderView view(long orderId) {
        return views(jdbc.sql(VIEW_SELECT + " WHERE o.id = ?").param(orderId).query(ViewRow.class).list()).getFirst();
    }

    private PullResult pull(Channel channel) {
        Instant started = clock.instant();
        Instant since = cursor(channel.id()).map(last -> last.minus(PULL_OVERLAP)).orElse(started.minus(FIRST_LOOKBACK));
        List<ExternalOrder> pulled = adapters.require(channel).pull(since);
        int created = 0;
        int manual = 0;
        for (ExternalOrder order : pulled) {
            Optional<Long> inserted = insert(channel, order);
            if (inserted.isPresent()) {
                created++;
            }
            long orderId = inserted.orElseGet(() -> existingId(channel.id(), order.externalOrderId()));
            if (process(orderId)) {
                manual++;
            }
        }
        saveCursor(channel.id(), started);
        return new PullResult(pulled.size(), created, manual);
    }

    /** @return 新落库的渠道单 id；已存在为空 */
    private Optional<Long> insert(Channel channel, ExternalOrder order) {
        String phone = Phones.normalize(order.buyerPhone());
        SensitiveCipher.Sealed sealed = phone == null ? null : cipher.seal(SensitiveField.BUYER_CONTACT, phone);
        try {
            return Optional.of(tx.execute(status -> {
                GeneratedKeyHolder keys = new GeneratedKeyHolder();
                jdbc.sql("""
                        INSERT INTO lr_channel_order (channel_id, external_order_id, status, buyer_contact_cipher, buyer_contact_hash, key_version,
                            raw_json, paid_fen, ordered_at, ordered_at_raw)
                        VALUES (?, ?, 'RECEIVED', ?, ?, ?, CAST(? AS JSON), ?, ?, ?)
                        """).params(channel.id(), order.externalOrderId(), sealed == null ? null : sealed.bytes(),
                        phone == null ? null : cipher.hash(SensitiveField.BUYER_CONTACT, phone), sealed == null ? null : sealed.keyVersion(),
                        order.rawJson(), order.paidFen(), UtcColumns.toColumn(order.orderedAt()), order.orderedAtRaw()).update(keys);
                long id = keys.getKey().longValue();
                for (ExternalOrderLine line : order.lines()) {
                    jdbc.sql("""
                            INSERT INTO lr_channel_order_line (channel_order_id, external_line_no, external_sku_id, qty, paid_fen)
                            VALUES (?, ?, ?, ?, ?)
                            """).params(id, line.lineNo(), line.externalSkuId(), line.qty(), line.paidFen()).update();
                }
                return id;
            }));
        } catch (DuplicateKeyException repeated) {
            return Optional.empty();
        }
    }

    /** 刚落库（或上次中断留在 RECEIVED）的单进履约；不能进则整单转人工。 @return 本次转了人工 */
    private boolean process(long orderId) {
        try {
            tx.executeWithoutResult(status -> {
                OrderRow order = lock(orderId);
                if (Status.RECEIVED.name().equals(order.status())) {
                    admit(order);
                }
            });
            return false;
        } catch (Unmapped e) {
            park(orderId, ManualTasks.Kind.UNMAPPED_CHANNEL_SKU, e.getMessage(), e.getMessage() + "；补映射后在渠道订单页重放");
            return true;
        } catch (FulfillmentRejected e) {
            String reason = e.reason().userMessage();
            park(orderId, ManualTasks.Kind.CHANNEL_ORDER_REJECTED, reason, "渠道单不能收单：" + reason + "（" + e.getMessage() + "）");
            return true;
        }
    }

    /** 在调用方事务内：整单映射 → 每行进唯一履约入口 + 订单行快照 → FULFILLING。 */
    private void admit(OrderRow order) {
        Channel channel = channels.require(order.channelId());
        List<LineRow> lines = jdbc.sql("""
                SELECT id, external_line_no, external_sku_id, qty, paid_fen FROM lr_channel_order_line WHERE channel_order_id = ? ORDER BY id
                """).param(order.id()).query(LineRow.class).list();
        Map<Long, String> mapped = new LinkedHashMap<>();
        List<String> unmapped = new ArrayList<>();
        for (LineRow line : lines) {
            mappings.mapped(channel.id(), line.externalSkuId()).ifPresentOrElse(sku -> mapped.put(line.id(), sku),
                    () -> unmapped.add(line.externalSkuId()));
        }
        if (!unmapped.isEmpty()) {
            throw new Unmapped(unmapped.stream().distinct().toList());
        }
        for (LineRow line : lines) {
            String sku = mapped.get(line.id());
            SkuBinding binding = bindings.findByShellSkuId(sku)
                    .orElseThrow(() -> new FulfillmentRejected(FulfillmentRejected.Reason.SKU_NOT_BOUND, sku));
            jdbc.sql("UPDATE lr_channel_order_line SET li_goods_sku_id = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?")
                    .params(sku, line.id()).update();
            String lineRef = channel.code() + ":" + order.externalOrderId() + ":" + line.externalLineNo();
            entry.accept(new FulfillmentRequest(FulfillmentRequest.SourceType.CHANNEL, order.externalOrderId(), lineRef, binding.id(), line.qty(),
                    null, null, line.id()));
            snapshots.record(new SnapshotRequest(SnapshotRequest.SourceType.CHANNEL, lineRef, null, binding.id(), line.paidFen() / line.qty(),
                    line.paidFen(), line.qty(), null));
        }
        jdbc.sql("""
                UPDATE lr_channel_order SET status = 'FULFILLING', last_error = NULL, version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?
                """).param(order.id()).update();
    }

    private void park(long orderId, ManualTasks.Kind kind, String error, String note) {
        tx.executeWithoutResult(status -> {
            OrderRow order = lock(orderId);
            jdbc.sql("""
                    UPDATE lr_channel_order SET status = 'MANUAL', last_error = ?, version = version + 1, updated_at = UTC_TIMESTAMP(3) WHERE id = ?
                    """).params(truncate(error), orderId).update();
            tasks.open(kind, TASK_REF, orderId, "渠道单 " + order.externalOrderId() + "：" + note, clock.instant());
        });
    }

    private OrderRow lock(long orderId) {
        return jdbc.sql("SELECT id, channel_id, external_order_id, status FROM lr_channel_order WHERE id = ? FOR UPDATE").param(orderId)
                .query(OrderRow.class).optional().orElseThrow(() -> new UserFacingException("找不到这个渠道单"));
    }

    private long existingId(long channelId, String externalOrderId) {
        return jdbc.sql("SELECT id FROM lr_channel_order WHERE channel_id = ? AND external_order_id = ?").params(channelId, externalOrderId)
                .query(Long.class).single();
    }

    /** 拉单游标 = 上次拉单开始时刻；存 {@code lr_setting}（CHANNEL 作用域）但不是运营配置，不走 Settings 以免每次刷审计。 */
    private Optional<Instant> cursor(long channelId) {
        return jdbc.sql("SELECT JSON_UNQUOTE(value_json) FROM lr_setting WHERE setting_key = ? AND scope_type = 'CHANNEL' AND scope_id = ?")
                .params(CURSOR_KEY, channelId).query(String.class).optional().map(Instant::parse);
    }

    private void saveCursor(long channelId, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO lr_setting (setting_key, scope_type, scope_id, value_json, updated_by)
                VALUES (?, 'CHANNEL', ?, JSON_QUOTE(?), 'SYSTEM')
                ON DUPLICATE KEY UPDATE value_json = VALUES(value_json), version = version + 1, updated_at = UTC_TIMESTAMP(3)
                """).params(CURSOR_KEY, channelId, startedAt.toString()).update();
    }

    private static final String VIEW_SELECT = """
            SELECT o.id, o.channel_id, c.code AS channel_code, o.external_order_id, o.status, o.paid_fen, o.ordered_at, o.recon_status,
                   o.claimed_member_id, o.claimed_at, o.last_error, o.created_at
              FROM lr_channel_order o JOIN lr_channel c ON c.id = o.channel_id
            """;

    private List<ChannelOrderView> views(List<ViewRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(ViewRow::id).toList();
        Map<Long, List<ChannelOrderView.Line>> lines = jdbc.sql("""
                SELECT l.channel_order_id, l.id, l.external_line_no, l.external_sku_id, l.li_goods_sku_id, l.qty, l.paid_fen,
                       fl.status AS fulfillment_status, l.writeback_status, l.writeback_attempts, l.writeback_error
                  FROM lr_channel_order_line l LEFT JOIN lr_fulfillment_line fl ON fl.channel_order_line_id = l.id
                 WHERE l.channel_order_id IN (:ids) ORDER BY l.id
                """).param("ids", ids).query(ViewLineRow.class).list().stream()
                .collect(Collectors.groupingBy(ViewLineRow::channelOrderId, LinkedHashMap::new, Collectors.mapping(l -> new ChannelOrderView.Line(
                        l.id(), l.externalLineNo(), l.externalSkuId(), l.liGoodsSkuId(), l.qty(), l.paidFen(), l.fulfillmentStatus(),
                        l.writebackStatus(), ChannelOrderView.writebackText(l.writebackStatus()), l.writebackAttempts(), l.writebackError()),
                        Collectors.toList())));
        return rows.stream().map(r -> new ChannelOrderView(r.id(), r.channelId(), r.channelCode(), r.externalOrderId(), r.status(),
                ChannelOrderView.statusText(r.status()), r.paidFen(), DisplayTime.format(UtcColumns.fromColumn(r.orderedAt())), r.reconStatus(),
                ChannelOrderView.reconText(r.reconStatus()), r.claimedMemberId() != null, DisplayTime.format(UtcColumns.fromColumn(r.claimedAt())),
                r.lastError(), DisplayTime.format(UtcColumns.fromColumn(r.createdAt())), lines.getOrDefault(r.id(), List.of()))).toList();
    }

    static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
