package com.liroam.fulfillment.card;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.liroam.catalog.SkuBinding.KycGateMode;
import com.liroam.common.time.UtcColumns;
import com.liroam.fulfillment.DisplayTime;
import com.liroam.fulfillment.state.CardAssetStatus;
import com.liroam.fulfillment.state.CardPlanStatus;
import com.liroam.fulfillment.state.KycStatus;

/** 台账行 → {@link CardView}：状态文案、流量/语音/短信余量、实名、到期时间（北京时间）都在这里定，买家端与后台同一份。 */
public final class CardViewProjector {

    /** 卡台账行（已联查卡产品、使用区域、供应商、履约行）。 */
    public record CardRow(long id, String cardNo, String productName, String usageRegionName, String form, CardAssetStatus status,
            boolean refundHold, String iccidLast4, String supplierCode, String ownerMemberId, String orderSn, LocalDateTime expiresAt,
            LocalDateTime lastPolledAt, LocalDateTime updatedAt, LocalDateTime createdAt, boolean capData, boolean capVoice, boolean capSms,
            String kycStatus, String kycGateMode) {
    }

    /** 套餐台账行（已联查套餐模板）；语音、短信的额度与已用为上游镜像值，没同步过为空。 */
    public record PlanRow(long id, long cardAssetId, String name, CardPlanStatus status, Long remainingBytes, LocalDateTime expiresAt,
            Long templateQuotaBytes, int validityDays, Long voiceRemainingSeconds, Long templateVoiceSeconds, Integer smsRemaining,
            Integer templateSmsCount, Long voiceQuotaSeconds, Long voiceUsedSeconds, Integer smsQuota, Integer smsUsed) {
    }

    /** 作废/终止后不能再申请；其余状态都可申请，批不批由退款规则定（已激活的默认驳回、后台可例外批）。 */
    private static final Set<CardAssetStatus> REFUND_CLOSED = EnumSet.of(CardAssetStatus.CANCELLED, CardAssetStatus.TERMINATED);
    private static final Set<CardPlanStatus> LIVE = EnumSet.of(CardPlanStatus.READY, CardPlanStatus.IN_USE);
    private static final String NONE = "—";

    private CardViewProjector() {
    }

    /** @param canTopUp 由调用方按 {@code FulfillmentRules} 判定 */
    public static CardView project(CardRow card, List<PlanRow> plans, boolean canTopUp) {
        List<PlanRow> live = plans.stream().filter(p -> LIVE.contains(p.status())).toList();
        KycStatus kyc = KycStatus.valueOf(card.kycStatus());
        boolean kycBlocking = KycGateMode.valueOf(card.kycGateMode()) == KycGateMode.BLOCK_DELIVERY && !kyc.passesGate();
        String statusText = card.status() == CardAssetStatus.PROVISIONED && kycBlocking ? kyc.text() : statusText(card.status());
        return new CardView(card.id(), card.cardNo(), card.productName(), card.usageRegionName(), card.form(), card.status().name(),
                statusText, remainingText(live), time(card.expiresAt()),
                time(card.lastPolledAt() != null ? card.lastPolledAt() : card.updatedAt()), canTopUp,
                canApplyRefund(card.status(), card.refundHold()), card.refundHold(), card.iccidLast4(), card.supplierCode(),
                capabilityText(card), card.capVoice() ? voiceText(live) : null, card.capSms() ? smsText(live) : null,
                kyc.name(), kyc.text(), kycBlocking, kyc.declarable() && !REFUND_CLOSED.contains(card.status()) && !card.refundHold(),
                plans.stream().map(p -> plan(card, p)).toList());
    }

    /** 「可申请退款」的唯一判定：退款冻结即有进行中的退款单，不能重复申请。 */
    public static boolean canApplyRefund(CardAssetStatus status, boolean refundHold) {
        return !refundHold && !REFUND_CLOSED.contains(status);
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

    private static CardView.PlanView plan(CardRow card, PlanRow p) {
        String expires = p.expiresAt() != null ? time(p.expiresAt())
                : p.status() == CardPlanStatus.READY || p.status() == CardPlanStatus.PENDING || p.status() == CardPlanStatus.PROVISIONING
                        ? "激活后 " + p.validityDays() + " 天有效" : NONE;
        List<PlanRow> self = LIVE.contains(p.status()) ? List.of(p) : List.of();
        return new CardView.PlanView(p.id(), p.name(), p.status().name(), planStatusText(p.status()), self.isEmpty() ? NONE : remainingText(self),
                card.capVoice() ? voiceText(self) : null, card.capSms() ? smsText(self) : null,
                card.capVoice() ? usageText(p.voiceQuotaSeconds(), p.voiceUsedSeconds(), p.voiceRemainingSeconds(), p.templateVoiceSeconds(), 60, "分钟") : null,
                card.capSms() ? usageText(toLong(p.smsQuota()), toLong(p.smsUsed()), toLong(p.smsRemaining()), toLong(p.templateSmsCount()), 1, "条") : null,
                expires);
    }

    /**
     * 「已用 X / 共 Y 单位」：额度取镜像值，没有则取模板；已用取镜像值，没有则额度减余量，都没同步过为 0。额度未知为「—」。
     * 分钟数按整分钟向下取。
     */
    private static String usageText(Long quota, Long used, Long remaining, Long templateQuota, int perUnit, String unit) {
        Long total = quota != null ? quota : templateQuota;
        if (total == null) {
            return NONE;
        }
        long spent = used != null ? used : remaining != null ? Math.max(total - remaining, 0) : 0;
        return "已用 " + spent / perUnit + " / 共 " + total / perUnit + " " + unit;
    }

    private static Long toLong(Integer n) {
        return n == null ? null : n.longValue();
    }

    private static String capabilityText(CardRow card) {
        List<String> caps = new ArrayList<>();
        if (card.capData()) {
            caps.add("流量");
        }
        if (card.capVoice()) {
            caps.add("语音");
        }
        if (card.capSms()) {
            caps.add("短信");
        }
        return String.join(" · ", caps);
    }

    /** 语音余量合计：没同步过用量的按模板额度算；都没有额度时为「—」。 */
    private static String voiceText(List<PlanRow> live) {
        long seconds = 0;
        boolean known = false;
        for (PlanRow p : live) {
            Long s = p.voiceRemainingSeconds() != null ? p.voiceRemainingSeconds() : p.templateVoiceSeconds();
            if (s != null) {
                seconds += Math.max(s, 0);
                known = true;
            }
        }
        return known ? seconds / 60 + " 分钟" : NONE;
    }

    private static String smsText(List<PlanRow> live) {
        long count = 0;
        boolean known = false;
        for (PlanRow p : live) {
            Integer n = p.smsRemaining() != null ? p.smsRemaining() : p.templateSmsCount();
            if (n != null) {
                count += Math.max(n, 0);
                known = true;
            }
        }
        return known ? count + " 条" : NONE;
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
