package com.liroam.fulfillment.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.liroam.common.crypto.SensitiveCipher.Sealed;
import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.KycStatus;

/**
 * 卡资产仓储；只允许 fulfillment 模块使用（架构检查）。
 * 状态迁移一律经 {@link #move}：先按状态机校验，再 {@code UPDATE … WHERE status = ?}（CAS），并发时只有一个赢。
 */
public class CardAssets {

    /** 加包校验需要的卡属性。 */
    public record TopUpView(long id, String ownerMemberId, long supplierId, CardAssetStatus status, boolean refundHold) {
    }

    /** 时间列为 UTC 墙钟（{@link UtcColumns}）。 */
    public record Card(
            long id,
            String cardNo,
            long fulfillmentLineId,
            int unitSeq,
            long supplierId,
            long cardProductId,
            Long regionId,
            Long regionGroupId,
            String form,
            String ownerMemberId,
            byte[] iccidCipher,
            Integer keyVersion,
            String iccidLast4,
            String upstreamRef,
            CardAssetStatus status,
            CardAssetStatus exceptionFrom,
            String kycStatus,
            boolean refundHold,
            String pollTier,
            LocalDateTime nextPollAt,
            LocalDateTime lastPolledAt,
            LocalDateTime refreshCooldownUntil,
            LocalDateTime activatedAt,
            LocalDateTime provisionedAt,
            LocalDateTime expiresAt,
            LocalDateTime createdAt) {

        public Optional<Sealed> sealedIccid() {
            return iccidCipher == null ? Optional.empty() : Optional.of(new Sealed(keyVersion, iccidCipher));
        }
    }

    public record NewCard(long fulfillmentLineId, int unitSeq, long supplierId, long cardProductId, Long regionId, Long regionGroupId,
            String form, String ownerMemberId, String kycStatus) {
    }

    private static final String SELECT = """
            SELECT id, card_no, fulfillment_line_id, unit_seq, supplier_id, card_product_id, region_id, region_group_id, form,
                   owner_member_id, iccid_cipher, key_version, iccid_last4, upstream_ref, status, exception_from, kyc_status, refund_hold,
                   poll_tier, next_poll_at, last_polled_at, refresh_cooldown_until, activated_at, provisioned_at, expires_at, created_at
              FROM lr_card_asset
            """;

    private final JdbcClient jdbc;

    public CardAssets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TopUpView> findForTopUp(long cardAssetId) {
        return jdbc.sql("SELECT id, owner_member_id, supplier_id, status, refund_hold FROM lr_card_asset WHERE id = ?")
                .param(cardAssetId).query(TopUpView.class).optional();
    }

    public Optional<Card> find(long id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(Card.class).optional();
    }

    /** 事务内加行锁读。 */
    public Optional<Card> lock(long id) {
        return jdbc.sql(SELECT + " WHERE id = ? FOR UPDATE").param(id).query(Card.class).optional();
    }

    public List<Card> byLine(long fulfillmentLineId) {
        return jdbc.sql(SELECT + " WHERE fulfillment_line_id = ? ORDER BY unit_seq").param(fulfillmentLineId).query(Card.class).list();
    }

    public Optional<Card> findByIccidHash(byte[] hash) {
        return jdbc.sql(SELECT + " WHERE iccid_hash = ?").param(hash).query(Card.class).optional();
    }

    /** 对外展示号 = LR + 10 位 id，插入后回填。 */
    public long insert(NewCard c) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO lr_card_asset (fulfillment_line_id, unit_seq, supplier_id, card_product_id, region_id, region_group_id, form,
                                           owner_member_id, kyc_status, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(c.fulfillmentLineId(), c.unitSeq(), c.supplierId(), c.cardProductId(), c.regionId(), c.regionGroupId(), c.form(),
                        c.ownerMemberId(), c.kycStatus(), CardAssetStatus.CREATED.name())
                .update(keys);
        long id = keys.getKey().longValue();
        jdbc.sql("UPDATE lr_card_asset SET card_no = CONCAT('LR', LPAD(id, 10, '0')) WHERE id = ?").param(id).update();
        return id;
    }

    /**
     * 状态迁移（CAS）。进入 EXCEPTION 时记 {@code exception_from}；离开 EXCEPTION 只能回到 {@code exception_from} 或作废/终止。
     *
     * @return false = 并发下状态已被他人改动
     */
    public boolean move(Card card, CardAssetStatus to, Instant now) {
        CardAssetStatus from = card.status();
        CardAssetStatus.MACHINE.requireMove(from, to);
        if (from == CardAssetStatus.EXCEPTION && !CardAssetStatus.canLeaveException(card.exceptionFrom(), to)) {
            throw new IllegalStateException("卡 " + card.id() + " 只能从 EXCEPTION 回到 " + card.exceptionFrom() + " 或作废/终止，不能到 " + to);
        }
        String exceptionFrom = to == CardAssetStatus.EXCEPTION ? from.name() : null;
        LocalDateTime at = UtcColumns.toColumn(now);
        return jdbc.sql("""
                UPDATE lr_card_asset
                   SET status = ?, exception_from = ?, version = version + 1, updated_at = ?,
                       provisioned_at = IF(? = 'PROVISIONED' AND provisioned_at IS NULL, ?, provisioned_at),
                       activated_at = IF(? = 'IN_USE' AND activated_at IS NULL, ?, activated_at)
                 WHERE id = ? AND status = ?
                """).params(to.name(), exceptionFrom, at, to.name(), at, to.name(), at, card.id(), from.name()).update() == 1;
    }

    /** 实名状态迁移（CAS）；@return false = 并发下已被他人改动 */
    public boolean moveKyc(long id, KycStatus from, KycStatus to) {
        KycStatus.MACHINE.requireMove(from, to);
        return jdbc.sql("UPDATE lr_card_asset SET kyc_status = ?, version = version + 1 WHERE id = ? AND kyc_status = ?")
                .params(to.name(), id, from.name()).update() == 1;
    }

    public void setIccid(long id, Sealed sealed, byte[] hash, String last4) {
        jdbc.sql("UPDATE lr_card_asset SET iccid_cipher = ?, key_version = ?, iccid_hash = ?, iccid_last4 = ? WHERE id = ?")
                .params(sealed.bytes(), sealed.keyVersion(), hash, last4, id).update();
    }

    /** 池卡归还后释放唯一键，让这张 ICCID 能再分配给别的卡。 */
    public void releaseIccidHash(long id) {
        jdbc.sql("UPDATE lr_card_asset SET iccid_hash = NULL WHERE id = ?").param(id).update();
    }

    public void setUpstreamRef(long id, String upstreamRef) {
        jdbc.sql("UPDATE lr_card_asset SET upstream_ref = ? WHERE id = ?").params(upstreamRef, id).update();
    }

    /** @return false = 已在冻结中 */
    public boolean hold(long id) {
        return jdbc.sql("UPDATE lr_card_asset SET refund_hold = 1, version = version + 1 WHERE id = ? AND refund_hold = 0").param(id).update() == 1;
    }

    public void release(long id) {
        jdbc.sql("UPDATE lr_card_asset SET refund_hold = 0, version = version + 1 WHERE id = ?").param(id).update();
    }

    /** 属主从空写成会员（触发器补 {@code owner_bound_at}）。@return false = 已有属主 */
    public boolean bindOwner(long id, String memberId) {
        return jdbc.sql("UPDATE lr_card_asset SET owner_member_id = ?, version = version + 1 WHERE id = ? AND owner_member_id IS NULL")
                .params(memberId, id).update() == 1;
    }

    /** {@code tier} 为空 = 停止轮询。 */
    public void schedulePoll(long id, String tier, Instant nextPollAt) {
        jdbc.sql("UPDATE lr_card_asset SET poll_tier = ?, next_poll_at = ? WHERE id = ?")
                .params(tier, UtcColumns.toColumn(nextPollAt), id).update();
    }

    public void mirrored(long id, Instant polledAt, Instant expiresAt) {
        jdbc.sql("UPDATE lr_card_asset SET last_polled_at = ?, expires_at = ? WHERE id = ?")
                .params(UtcColumns.toColumn(polledAt), UtcColumns.toColumn(expiresAt), id).update();
    }

    /** 冷却未到返回 false；到了则写入新的冷却截止时间。 */
    public boolean startRefreshCooldown(long id, Instant now, Instant until, boolean force) {
        return jdbc.sql("""
                UPDATE lr_card_asset SET refresh_cooldown_until = ?
                 WHERE id = ? AND (? OR refresh_cooldown_until IS NULL OR refresh_cooldown_until <= ?)
                """).params(UtcColumns.toColumn(until), id, force, UtcColumns.toColumn(now)).update() == 1;
    }
}
