package cn.lili.event.impl;

import cn.lili.cache.Cache;
import cn.lili.modules.member.entity.enums.PointSourceEnum;
import cn.lili.modules.member.entity.enums.PointTypeEnum;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.entity.dto.OrderMessage;
import cn.lili.modules.order.order.entity.dto.PriceDetailDTO;
import cn.lili.modules.order.order.entity.enums.OrderStatusEnum;
import cn.lili.modules.order.order.entity.enums.PayStatusEnum;
import cn.lili.modules.order.order.service.OrderService;
import cn.lili.modules.system.service.SettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 订单取消时积分返还单元测试
 *
 * @author Bulbasaur
 * @since 2026-11-11
 */
class MemberPointExecuteCancelTest {

    private MemberPointExecute memberPointExecute;
    private MemberService memberService;
    private OrderService orderService;
    private Cache cache;

    @BeforeEach
    void setUp() {
        memberService = mock(MemberService.class);
        orderService = mock(OrderService.class);
        SettingService settingService = mock(SettingService.class);
        cache = mock(Cache.class);

        memberPointExecute = new MemberPointExecute(settingService, memberService, orderService, cache);

        when(memberService.updateMemberPoint(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    /** 验证重复通知拦截所需的缓存缺失时拒绝装配，禁止无缓存启动后直接返还积分。 */
    @Test
    void missingCachePreventsInitialization() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("settings", mock(SettingService.class));
            context.getBeanFactory().registerSingleton("members", memberService);
            context.getBeanFactory().registerSingleton("orders", orderService);
            context.register(MemberPointExecute.class);
            assertThatThrownBy(context::refresh).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining(Cache.class.getName());
        }
    }

    /** 验证缓存读取异常会中断处理，不绕过重复通知检查直接增加会员积分。 */
    @Test
    void cacheReadFailurePreventsPointRefund() {
        OrderMessage message = cancelledPointOrder("CACHE_FAIL");
        when(cache.hasKey("order:point:refund:CACHE_FAIL")).thenThrow(new IllegalStateException("缓存不可用"));

        assertThatThrownBy(() -> memberPointExecute.orderChange(message))
                .isInstanceOf(IllegalStateException.class).hasMessage("缓存不可用");
        verifyNoInteractions(memberService);
    }

    /** 验证会员积分更新未成功时不写已处理标记，保留后续通知重试的机会。 */
    @Test
    void failedPointUpdateDoesNotWriteProcessedMarker() {
        OrderMessage message = cancelledPointOrder("UPDATE_FAIL");
        when(memberService.updateMemberPoint(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        memberPointExecute.orderChange(message);

        verify(memberService).updateMemberPoint(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(cache, never()).put(any(), any(), anyLong(), any(TimeUnit.class));
    }

    /**
     * 准备使用100积分的取消订单，并配置订单服务返回该成交数据。
     *
     * @param orderSn 订单编号
     * @return 对应的订单取消消息
     */
    private OrderMessage cancelledPointOrder(String orderSn) {
        Order order = new Order();
        order.setSn(orderSn);
        order.setMemberId("member");
        PriceDetailDTO price = new PriceDetailDTO();
        price.setPayPoint(100L);
        order.setPriceDetailDTO(price);
        when(orderService.getBySn(orderSn)).thenReturn(order);
        OrderMessage message = new OrderMessage();
        message.setOrderSn(orderSn);
        message.setNewStatus(OrderStatusEnum.CANCELLED);
        return message;
    }

    /**
     * 未付款订单取消时，下单扣减的积分必须退回
     */
    @Test
    @DisplayName("未付款订单取消时下单扣减积分必须退回")
    void unpaidOrderCancelledRefundsDeductedPoints() {
        Order order = new Order();
        order.setSn("O_POINT_001");
        order.setMemberId("M_001");
        order.setOrderStatus(OrderStatusEnum.CANCELLED.name());
        order.setPayStatus(PayStatusEnum.UNPAID.name());

        PriceDetailDTO priceDetailDTO = new PriceDetailDTO();
        priceDetailDTO.setPayPoint(150L);
        order.setPriceDetailDTO(priceDetailDTO);

        when(orderService.getBySn("O_POINT_001")).thenReturn(order);
        when(cache.hasKey("order:point:refund:O_POINT_001")).thenReturn(false);

        OrderMessage message = new OrderMessage();
        message.setOrderSn("O_POINT_001");
        message.setNewStatus(OrderStatusEnum.CANCELLED);

        memberPointExecute.orderChange(message);

        // 验证调用了加积分
        verify(memberService).updateMemberPoint(
                eq(150L),
                eq(PointTypeEnum.INCREASE.name()),
                eq("M_001"),
                eq("订单取消，积分返还：150分"),
                eq(PointSourceEnum.ORDER.name())
        );

        // 验证写入缓存防重
        verify(cache).put(eq("order:point:refund:O_POINT_001"), eq(1), eq(7L), eq(TimeUnit.DAYS));
    }

    /**
     * 重复收到订单取消事件时幂等防重，不重复返还积分
     */
    @Test
    @DisplayName("重复收到订单取消事件时幂等防重不重复返还积分")
    void cancelledOrderPointRefundIsIdempotent() {
        Order order = new Order();
        order.setSn("O_POINT_002");
        order.setMemberId("M_002");
        order.setOrderStatus(OrderStatusEnum.CANCELLED.name());
        order.setPayStatus(PayStatusEnum.UNPAID.name());

        PriceDetailDTO priceDetailDTO = new PriceDetailDTO();
        priceDetailDTO.setPayPoint(200L);
        order.setPriceDetailDTO(priceDetailDTO);

        when(orderService.getBySn("O_POINT_002")).thenReturn(order);
        // 模拟已退还过缓存中存在标记
        when(cache.hasKey("order:point:refund:O_POINT_002")).thenReturn(true);

        OrderMessage message = new OrderMessage();
        message.setOrderSn("O_POINT_002");
        message.setNewStatus(OrderStatusEnum.CANCELLED);

        memberPointExecute.orderChange(message);

        // 验证未再次调用加积分
        verify(memberService, never()).updateMemberPoint(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 未使用积分的订单取消时不触发积分返还
     */
    @Test
    @DisplayName("未使用积分的订单取消时不触发积分返还")
    void orderWithoutPointsDoesNotTriggerRefund() {
        Order order = new Order();
        order.setSn("O_POINT_003");
        order.setMemberId("M_003");
        order.setOrderStatus(OrderStatusEnum.CANCELLED.name());
        order.setPayStatus(PayStatusEnum.UNPAID.name());

        PriceDetailDTO priceDetailDTO = new PriceDetailDTO();
        priceDetailDTO.setPayPoint(0L);
        order.setPriceDetailDTO(priceDetailDTO);

        when(orderService.getBySn("O_POINT_003")).thenReturn(order);

        OrderMessage message = new OrderMessage();
        message.setOrderSn("O_POINT_003");
        message.setNewStatus(OrderStatusEnum.CANCELLED);

        memberPointExecute.orderChange(message);

        verify(memberService, never()).updateMemberPoint(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
}
