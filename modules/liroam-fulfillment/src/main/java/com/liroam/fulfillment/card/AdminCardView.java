package com.liroam.fulfillment.card;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** 后台卡列表行：卡投影平铺 + 属主、订单号、建卡时间。 */
public record AdminCardView(@JsonUnwrapped CardView card, String ownerMemberId, String orderSn, String createdText) {

    /** 后台卡详情：列表行 + 指令流水。 */
    public record Detail(@JsonUnwrapped AdminCardView row, List<CommandView> commands) {

        public Detail {
            commands = List.copyOf(commands);
        }
    }

    public record CommandView(long id, String type, String status, int attempts, String errorClass, String errorMsg, String nextAttemptText,
            String updatedText) {
    }
}
