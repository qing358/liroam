package com.liroam.ops.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liroam.common.web.ApiResult;
import com.liroam.common.web.CurrentActor;
import com.liroam.ops.notify.Notifications;

/** 买家站内通知（「我的卡」顶部到期提示）。 */
@RestController
@RequestMapping("/buyer/liroam/notifications")
public class BuyerNotificationController {

    private final CurrentActor actor;
    private final Notifications notifications;

    public BuyerNotificationController(CurrentActor actor, Notifications notifications) {
        this.actor = actor;
        this.notifications = notifications;
    }

    @GetMapping
    public ApiResult<Notifications.Inbox> mine() {
        return ApiResult.ok(notifications.mine(actor.requireMemberId()));
    }

    @PostMapping("/read")
    public ApiResult<Notifications.Inbox> read() {
        String memberId = actor.requireMemberId();
        notifications.markAllRead(memberId);
        return ApiResult.ok(notifications.mine(memberId));
    }
}
