package cn.lili.event.impl;

import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.order.order.entity.dto.OrderMessage;
import cn.lili.modules.order.order.entity.enums.OrderStatusEnum;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.system.service.SettingService;
import org.junit.jupiter.api.Test;
import cn.lili.cache.Cache;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 订单完成消息不发放或登记购物积分，发放统一交给关闭售后任务。
 *
 * @author Bulbasaur
 * @since 2026-11-05
 */
class MemberPointExecuteCompletionTest {
    /**
     * 确认收货的完成事件不得查询订单或变更积分余额。
     */
    @Test
    void completionEventDoesNotAwardShoppingPoints() {
        var members = mock(MemberService.class);
        var orders = mock(OrderService.class);
        var settings = mock(SettingService.class);
        var execute = new MemberPointExecute(settings, members, orders, mock(Cache.class));
        var message = new OrderMessage();
        message.setOrderSn("order");
        message.setNewStatus(OrderStatusEnum.COMPLETED);
        execute.orderChange(message);
        verifyNoInteractions(members, orders, settings);
    }
}
