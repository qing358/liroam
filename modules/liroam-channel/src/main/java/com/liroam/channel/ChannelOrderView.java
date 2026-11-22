package com.liroam.channel;

import java.util.List;

/**
 * 后台渠道订单一行：状态、对账标记、是否已被买家认领，及每行的映射、履约与回传进度。收货手机号不展示。
 *
 * @param reconStatus BALANCED / DIFF；未对过账为空
 */
public record ChannelOrderView(long id, long channelId, String channelCode, String externalOrderId, String status, String statusText, long paidFen,
        String orderedText, String reconStatus, String reconText, boolean claimed, String claimedText, String lastError, String createdText,
        List<Line> lines) {

    /** @param fulfillmentStatus 还没进履约为空 */
    public record Line(long id, String externalLineNo, String externalSkuId, String liGoodsSkuId, int qty, long paidFen, String fulfillmentStatus,
            String writebackStatus, String writebackText, int writebackAttempts, String writebackError) {
    }

    static String statusText(String status) {
        return switch (status) {
            case "RECEIVED" -> "已接收";
            case "FULFILLING" -> "履约中";
            case "DONE" -> "已完成";
            case "MANUAL" -> "待人工处理";
            default -> status;
        };
    }

    static String reconText(String reconStatus) {
        if (reconStatus == null) {
            return "未对账";
        }
        return switch (reconStatus) {
            case "BALANCED" -> "已对平";
            case "DIFF" -> "有差异";
            default -> reconStatus;
        };
    }

    static String writebackText(String status) {
        return switch (status) {
            case "PENDING" -> "待交码";
            case "READY" -> "待回传";
            case "FAILED" -> "回传失败，重试中";
            case "DONE" -> "已回传";
            case "MANUAL" -> "回传失败，待人工";
            default -> status;
        };
    }
}
