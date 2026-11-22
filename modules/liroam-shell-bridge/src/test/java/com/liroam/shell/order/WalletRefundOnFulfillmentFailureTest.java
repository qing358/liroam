package com.liroam.shell.order;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.liroam.fulfillment.FulfillmentRejected;
import com.liroam.fulfillment.FulfillmentRejected.Reason;

import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.modules.order.order.event.OrderPaidEvent;
import cn.lili.modules.payment.kit.dto.PayParam;
import cn.lili.modules.payment.kit.params.dto.CashierParam;
import cn.lili.modules.payment.kit.plugin.wallet.WalletPlugin;
import cn.lili.modules.payment.service.PaymentService;
import cn.lili.modules.wallet.entity.dto.MemberWalletUpdateDTO;
import cn.lili.modules.wallet.service.MemberWalletService;

/**
 * 上游 {@code WalletPlugin}：余额先独立扣款提交，只在 {@code paymentService.success} 抛 {@link ServiceException} 时退款。
 * 用真插件 + 我方监听器验证：收单失败（任何异常）都会让余额退回。
 */
@ExtendWith(MockitoExtension.class)
class WalletRefundOnFulfillmentFailureTest {

    @Mock
    PaymentService paymentService;
    @Mock
    MemberWalletService memberWalletService;
    @InjectMocks
    WalletPlugin walletPlugin;

    @BeforeEach
    void login() {
        var auth = new UsernamePasswordAuthenticationToken("buyer", null, java.util.List.of());
        auth.setDetails(new AuthUser("buyer", "m1", "buyer", null, UserEnums.MEMBER));
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(memberWalletService.reduce(any())).thenReturn(true);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 让支付成功回调同步发布 OrderPaidEvent，交给 handler（与壳 payOrder 内发布同一条调用链）。 */
    private void paySucceedsAndPublishes(Consumer<OrderPaidEvent> handler) {
        doAnswer(inv -> {
            Order order = new Order();
            order.setSn("O-1");
            order.setMemberId("m1");
            handler.accept(new OrderPaidEvent(this, order));
            return null;
        }).when(paymentService).success(any());
    }

    private void payByWallet() {
        CashierParam cashier = new CashierParam();
        cashier.setPrice(12.5);
        cashier.setOrderSns("O-1");
        walletPlugin.callBack(new PayParam(), cashier);
    }

    private static boolean isRefund(MemberWalletUpdateDTO dto) {
        return "WALLET_REFUND".equals(dto.getServiceType()) && dto.getMoney() == 12.5 && "m1".equals(dto.getMemberId());
    }

    @Test
    void upstreamBaseline_nonServiceExceptionKeepsTheMoney() {
        paySucceedsAndPublishes(e -> {
            throw new IllegalStateException("db down");
        });

        assertThatThrownBy(this::payByWallet).isInstanceOf(ServiceException.class);
        verify(memberWalletService, never()).increase(any());
    }

    @Test
    void storageFailureInListenerRefundsWallet() {
        PaidOrderFulfillment fulfillment = mock(PaidOrderFulfillment.class);
        when(fulfillment.fulfill(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("db down"));
        var listener = new OrderPaidFulfillmentListener(fulfillment);
        paySucceedsAndPublishes(listener::onOrderPaid);

        assertThatThrownBy(this::payByWallet).isInstanceOf(ServiceException.class);
        verify(memberWalletService).increase(argThat(WalletRefundOnFulfillmentFailureTest::isRefund));
    }

    @Test
    void rejectedFulfillmentRefundsWallet() {
        PaidOrderFulfillment fulfillment = mock(PaidOrderFulfillment.class);
        when(fulfillment.fulfill(anyString(), anyString())).thenThrow(new FulfillmentRejected(Reason.SKU_NOT_LISTED, "S1"));
        var listener = new OrderPaidFulfillmentListener(fulfillment);
        paySucceedsAndPublishes(listener::onOrderPaid);

        assertThatThrownBy(this::payByWallet).isInstanceOf(ServiceException.class).hasMessageContaining("SKU_NOT_LISTED");
        verify(memberWalletService).increase(argThat(WalletRefundOnFulfillmentFailureTest::isRefund));
    }

    @Test
    void successfulFulfillmentDoesNotRefund() {
        PaidOrderFulfillment fulfillment = mock(PaidOrderFulfillment.class);
        var listener = new OrderPaidFulfillmentListener(fulfillment);
        paySucceedsAndPublishes(listener::onOrderPaid);

        payByWallet();

        verify(fulfillment).fulfill("O-1", "m1");
        verify(memberWalletService, never()).increase(any());
    }
}
