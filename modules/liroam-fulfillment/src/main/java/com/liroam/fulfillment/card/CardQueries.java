package com.liroam.fulfillment.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import com.liroam.common.crypto.SensitiveCipher;
import com.liroam.common.crypto.SensitiveField;
import com.liroam.common.time.UtcColumns;
import com.liroam.common.web.PageResult;
import com.liroam.common.web.UserFacingException;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.fulfillment.FulfillmentRules;
import com.liroam.fulfillment.card.AdminCardView.CommandView;
import com.liroam.fulfillment.card.CardViewProjector.CardRow;
import com.liroam.fulfillment.card.CardViewProjector.PlanRow;
import com.liroam.fulfillment.poll.PollTiers;
import com.liroam.fulfillment.store.CardAssets;
import com.liroam.fulfillment.store.CardCredentials;
import com.liroam.fulfillment.store.CardCredentials.Credential;
import com.liroam.fulfillment.store.SupplierCommands;

/** 卡的读侧：买家只看自己的卡；查看激活码留敏感访问日志；后台分页筛选与详情。 */
public class CardQueries {

    /** 后台筛选；{@code keyword} 匹配卡号、属主、订单号、ICCID 尾四位。页码从 1 开始。 */
    public record CardFilter(String status, String supplierCode, String keyword, int pageNumber, int pageSize) {
    }

    /** 实体卡（{@code kind = SHIPMENT}）无 {@code payload}，给物流公司与运单号。 */
    public record CredentialView(String kind, String payload, String channel, String deliveredText, String logisticsCompany, String logisticsNo) {

        @Override
        public String toString() {
            return "CredentialView[kind=" + kind + ", payload=***]";
        }
    }

    /** @param skuId 壳 {@code li_goods_sku.id}，下单用 */
    public record TopUpOption(String skuId, String goodsId, String name, long priceFen) {
    }

    private static final String CARD_SELECT = """
            SELECT c.id, c.card_no, p.name AS product_name, COALESCE(r.name_zh, g.name_zh) AS usage_region_name, c.form, c.status, c.refund_hold,
                   c.iccid_last4, s.code AS supplier_code, c.owner_member_id, l.source_ref AS order_sn, c.expires_at, c.last_polled_at,
                   c.updated_at, c.created_at, p.cap_data, p.cap_voice, p.cap_sms, c.kyc_status, b.kyc_gate_mode
              FROM lr_card_asset c
              JOIN lr_card_product p ON p.id = c.card_product_id
              JOIN lr_supplier s ON s.id = c.supplier_id
              JOIN lr_fulfillment_line l ON l.id = c.fulfillment_line_id
              JOIN lr_sku_binding b ON b.id = l.sku_binding_id
              LEFT JOIN lr_usage_region r ON r.id = c.region_id
              LEFT JOIN lr_region_group g ON g.id = c.region_group_id
            """;
    private static final String PLAN_SELECT = """
            SELECT cp.id, cp.card_asset_id, t.name, cp.status, cp.remaining_bytes, cp.expires_at, t.data_quota_bytes AS template_quota_bytes,
                   t.validity_days, cp.voice_remaining_seconds, t.voice_seconds AS template_voice_seconds, cp.sms_remaining,
                   t.sms_count AS template_sms_count
              FROM lr_card_plan cp JOIN lr_plan_template t ON t.id = cp.plan_template_id
            """;

    private final TransactionOperations tx;
    private final JdbcClient jdbc;
    private final CardAssets cards;
    private final CardCredentials credentials;
    private final SupplierCommands commands;
    private final FulfillmentRules rules;
    private final SensitiveCipher cipher;

    public CardQueries(TransactionOperations tx, JdbcClient jdbc, CardAssets cards, CardCredentials credentials, SupplierCommands commands,
            FulfillmentRules rules, SensitiveCipher cipher) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.cards = cards;
        this.credentials = credentials;
        this.commands = commands;
        this.rules = rules;
        this.cipher = cipher;
    }

    public List<CardView> listForMember(String memberId) {
        List<CardRow> rows = jdbc.sql(CARD_SELECT + " WHERE c.owner_member_id = ? ORDER BY c.id DESC").param(memberId).query(CardRow.class).list();
        return project(rows, memberId);
    }

    public Optional<CardView> getForMember(long cardAssetId, String memberId) {
        return ownedRow(cardAssetId, memberId).map(row -> project(List.of(row), memberId).getFirst());
    }

    /**
     * 买家查看激活码：只给自己的、已交付的卡；退款处理中不出码。每次查看写 {@code lr_sensitive_access_log}。
     *
     * @return empty = 还没有可看的激活码
     */
    public Optional<CredentialView> credentialForMember(long cardAssetId, String memberId) {
        CardRow row = ownedRow(cardAssetId, memberId).orElseThrow(() -> new UserFacingException("找不到这张卡"));
        if (row.refundHold()) {
            throw new UserFacingException("该卡退款处理中，暂不能查看激活码");
        }
        if (!PollTiers.pollable(row.status())) {
            return Optional.empty();
        }
        return tx.execute(status -> credentials.current(cardAssetId).map(c -> {
            jdbc.sql("""
                    INSERT INTO lr_sensitive_access_log (operator_type, operator_id, target_type, target_id, field, reason)
                    VALUES ('MEMBER', ?, 'CARD', ?, 'CREDENTIAL', '买家查看激活码')
                    """).params(memberId, Long.toString(cardAssetId)).update();
            return view(c);
        }));
    }

    public PageResult<AdminCardView> page(CardFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (filter.status() != null && !filter.status().isBlank()) {
            where.append(" AND c.status = ?");
            args.add(filter.status().trim());
        }
        if (filter.supplierCode() != null && !filter.supplierCode().isBlank()) {
            where.append(" AND s.code = ?");
            args.add(filter.supplierCode().trim());
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String kw = filter.keyword().trim();
            where.append(" AND (c.card_no = ? OR c.owner_member_id = ? OR l.source_ref = ? OR c.iccid_last4 = ?)");
            args.addAll(List.of(kw, kw, kw, kw));
        }
        int size = PageResult.clampSize(filter.pageSize());
        int current = Math.max(filter.pageNumber(), 1);
        long total = jdbc.sql("SELECT COUNT(*) FROM (" + CARD_SELECT + where + ") t").params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(PageResult.offset(current, size));
        List<CardRow> rows = jdbc.sql(CARD_SELECT + where + " ORDER BY c.id DESC LIMIT ? OFFSET ?").params(pageArgs).query(CardRow.class).list();
        List<CardView> views = project(rows, null);
        List<AdminCardView> records = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            records.add(admin(rows.get(i), views.get(i)));
        }
        return new PageResult<>(records, total, current, size);
    }

    public Optional<AdminCardView.Detail> detail(long cardAssetId) {
        return jdbc.sql(CARD_SELECT + " WHERE c.id = ?").param(cardAssetId).query(CardRow.class).optional().map(row -> {
            CardView view = project(List.of(row), null).getFirst();
            List<CommandView> history = commands.byCard(cardAssetId).reversed().stream()
                    .map(m -> new CommandView(m.id(), m.type().name(), m.status().name(), m.attempts(), m.errorClass(), m.errorMsg(),
                            DisplayTime.format(UtcColumns.fromColumn(m.nextAttemptAt())), DisplayTime.format(UtcColumns.fromColumn(m.updatedAt()))))
                    .toList();
            return new AdminCardView.Detail(admin(row, view), history);
        });
    }

    /** 这张卡能买的加包：同供应商同卡产品、已上架且有生效售价的加包 SKU；卡不能加包时为空。 */
    public List<TopUpOption> topUpOptions(long cardAssetId, String memberId) {
        if (ownedRow(cardAssetId, memberId).isEmpty() || !canTopUp(cardAssetId, memberId)) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT b.li_goods_sku_id AS sku_id, b.li_goods_id AS goods_id, t.name, v.price_cny_fen AS price_fen
                  FROM lr_sku_binding b
                  JOIN lr_card_asset c ON c.supplier_id = b.supplier_id AND c.card_product_id = b.card_product_id
                  JOIN lr_supplier s ON s.id = b.supplier_id AND s.enabled = 1 AND s.listing_enabled = 1
                  JOIN lr_plan_template t ON t.id = b.plan_template_id
                  JOIN lr_sku_price_version v ON v.sku_binding_id = b.id AND v.status = 'ACTIVE'
                 WHERE c.id = ? AND b.sku_kind = 'TOP_UP' AND b.listing_state = 'LISTED'
                 ORDER BY v.price_cny_fen, b.id
                """).param(cardAssetId).query(TopUpOption.class).list();
    }

    private Optional<CardRow> ownedRow(long cardAssetId, String memberId) {
        return jdbc.sql(CARD_SELECT + " WHERE c.id = ? AND c.owner_member_id = ?").params(cardAssetId, memberId).query(CardRow.class).optional();
    }

    private List<CardView> project(List<CardRow> rows, String viewerMemberId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PlanRow>> plans = jdbc.sql(PLAN_SELECT + " WHERE cp.card_asset_id IN (:ids) ORDER BY cp.id")
                .param("ids", rows.stream().map(CardRow::id).toList()).query(PlanRow.class).list().stream()
                .collect(Collectors.groupingBy(PlanRow::cardAssetId));
        return rows.stream().map(r -> CardViewProjector.project(r, plans.getOrDefault(r.id(), List.of()),
                canTopUp(r.id(), viewerMemberId != null ? viewerMemberId : r.ownerMemberId()))).toList();
    }

    /** 与加包下单同一份规则（{@link FulfillmentRules#topUpBlocker}），且卡已交付。 */
    private boolean canTopUp(long cardAssetId, String memberId) {
        return cards.findForTopUp(cardAssetId)
                .filter(card -> PollTiers.pollable(card.status()))
                .filter(card -> rules.topUpBlocker(card, memberId, card.supplierId()).isEmpty())
                .isPresent();
    }

    private static AdminCardView admin(CardRow row, CardView view) {
        return new AdminCardView(view, row.ownerMemberId(), row.orderSn(), DisplayTime.format(UtcColumns.fromColumn(row.createdAt())));
    }

    private CredentialView view(Credential c) {
        String payload = c.sealedPayload().map(s -> cipher.open(SensitiveField.CREDENTIAL, s)).orElse(null);
        return new CredentialView(c.kind(), payload, c.channel(), DisplayTime.format(UtcColumns.fromColumn(c.deliveredAt())), c.logisticsCompany(),
                c.logisticsNo());
    }
}
