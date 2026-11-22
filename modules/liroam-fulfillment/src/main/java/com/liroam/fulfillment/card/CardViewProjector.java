package com.liroam.fulfillment.card;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.CardPlanStatus;

/** 台账行 → {@link CardView}：状态文案、余量、到期时间（北京时间）都在这里定，买家端与后台同一份。 */
public final class CardViewProjector {

    /** 卡台账行（已联查卡产品、使用区域、供应商、履约行）。 */
    public record CardRow(long id, String cardNo, String productName, String usageRegionName, String form, CardAssetStatus status,
            boolean refundHold, String iccidLast4, String supplierCode, String ownerMemberId, String orderSn, LocalDateTime expiresAt,
            LocalDateTime lastPolledAt, LocalDateTime updatedAt, LocalDateTime createdAt) {
    }

    /** 套餐台账行（已联查套餐模板）。 */
    public record PlanRow(long id, long cardAssetId, String name, CardPlanStatus status, Long remainingBytes, LocalDateTime expiresAt,
            Long templateQuotaBytes, int validityDays) {
    }

    /** 尚未激活、可整卡退款的状态；已激活或已结束的卡走售后，不在这里放开。 */
    private static final Set<CardAssetStatus> REFUNDABLE = EnumSet.of(CardAssetStatus.CREATED, CardAssetStatus.ALLOCATED,
            CardAssetStatus.PROVISIONED, CardAssetStatus.DELIVERED, CardAssetStatus.EXCEPTION);
    private static final Set<CardPlanStatus> LIVE = EnumSet.of(CardPlanStatus.READY, CardPlanStatus.IN_USE);
    private static final String NONE = "—";

    private CardViewProjector() {
    }

    /** @param canTopUp 由调用方按 {@code FulfillmentRules} 判定 */
    public static CardView project(CardRow card, List<PlanRow> plans, boolean canTopUp) {
        List<PlanRow> live = plans.stream().filter(p -> LIVE.contains(p.status())).toList();
        return new CardView(card.id(), card.cardNo(), card.productName(), card.usageRegionName(), card.form(), card.status().name(),
                statusText(card.status()), remainingText(live), time(card.expiresAt()),
                time(card.lastPolledAt() != null ? card.lastPolledAt() : card.updatedAt()), canTopUp,
                !card.refundHold() && REFUNDABLE.contains(card.status()), card.refundHold(), card.iccidLast4(), card.supplierCode(),
                plans.stream().map(CardViewProjector::plan).toList());
    }

    public static String statusText(CardAssetStatus status) {
        return switch (status) {
            case CREATED, ALLOCATED -> "开通中";
            case PROVISIONED -> "待交付";
            case DELIVERED -> "待激活";
            case IN_USE -> "使用中";
            case SUSPENDED -> "已停用";
            case EXPIRED -> "已到期";
            case CANCELLED -> "已作废";
            case TERMINATED -> "已终止";
            case EXCEPTION -> "处理中";
        };
    }

    public static String planStatusText(CardPlanStatus status) {
        return switch (status) {
            case PENDING, PROVISIONING -> "开通中";
            case READY -> "未激活";
            case IN_USE -> "使用中";
            case DEPLETED -> "已用完";
            case EXPIRED -> "已过期";
            case CANCELLED -> "已取消";
            case FAILED -> "开通失败";
        };
    }

    private static CardView.PlanView plan(PlanRow p) {
        String expires = p.expiresAt() != null ? time(p.expiresAt())
                : p.status() == CardPlanStatus.READY || p.status() == CardPlanStatus.PENDING || p.status() == CardPlanStatus.PROVISIONING
                        ? "激活后 " + p.validityDays() + " 天有效" : NONE;
        String remaining = LIVE.contains(p.status()) ? remainingText(List.of(p)) : NONE;
        return new CardView.PlanView(p.id(), p.name(), p.status().name(), planStatusText(p.status()), remaining, expires);
    }

    /** 可用套餐余量合计：有一个不限量即「不限量」；没同步过用量的按模板额度算。 */
    private static String remainingText(List<PlanRow> live) {
        if (live.isEmpty()) {
            return NONE;
        }
        long total = 0;
        for (PlanRow p : live) {
            Long bytes = p.remainingBytes() != null ? p.remainingBytes() : p.templateQuotaBytes();
            if (bytes == null) {
                return "不限量";
            }
            total += Math.max(bytes, 0);
        }
        return bytes(total);
    }

    static String bytes(long bytes) {
        double gb = bytes / (1024d * 1024 * 1024);
        if (gb >= 1) {
            return trim(gb) + " GB";
        }
        double mb = bytes / (1024d * 1024);
        if (mb >= 1) {
            return trim(mb) + " MB";
        }
        return bytes + " B";
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
    }

    private static String time(LocalDateTime column) {
        return column == null ? NONE : DisplayTime.format(UtcColumns.fromColumn(column));
    }
}
