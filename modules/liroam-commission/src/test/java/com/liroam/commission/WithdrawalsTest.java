package com.liroam.commission;

import static com.liroam.commission.CommissionFixture.A1;
import static com.liroam.commission.CommissionFixture.A2;
import static com.liroam.commission.CommissionFixture.OP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.commission.AgentDirectory.AgentChain;
import com.liroam.commission.CommissionPolicies.PolicyInput;
import com.liroam.commission.Withdrawals.Payee;
import com.liroam.common.web.UserFacingException;

/** 代理提现（domain-design §10）：无出金通道 = 申请 → 审批 → 人工打款登记；在途金额占用可提现；驳回释放；收款信息加密只露掩码。 */
class WithdrawalsTest {

    private static CommissionFixture f;
    private static final Payee PAYEE = new Payee("ALIPAY", "张三", "13800001111");

    @BeforeAll
    static void start() {
        f = CommissionFixture.start("commission_withdrawals");
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
        f.paidRate("0.10", "0.8", "0.2", 0);
        f.deliveredCard("O-W", 10_000, new AgentChain(A1, A2));
        f.accruals.runOnce(50);
    }

    @Test
    void applyApprovePayWritesWithdrawEntry() {
        Withdrawals.View w = f.withdrawals.apply(A1.memberId(), 500, PAYEE);
        assertThat(w.status()).isEqualTo("APPLIED");
        assertThat(w.payeeMasked()).isEqualTo("支付宝 张* 138****1111");
        assertThat(f.withdrawals.summary(A1.memberId()).availableFen()).isEqualTo(300);

        assertThatThrownBy(() -> f.withdrawals.apply(A1.memberId(), 301, PAYEE)).isInstanceOf(UserFacingException.class)
                .hasMessageContaining("可提现");

        f.withdrawals.approve(w.id(), OP);
        Withdrawals.View paid = f.withdrawals.markPaid(w.id(), OP, "银行回单 123", "已线下转账");
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(f.amounts("D1", "WITHDRAW")).containsExactly(-500L);
        CommissionLedger.Balance b = f.ledger.balance("D1");
        assertThat(b.withdrawnFen()).isEqualTo(500);
        assertThat(b.settleableFen()).isEqualTo(300);
        assertThat(f.withdrawals.payee(w.id(), OP)).isEqualTo(PAYEE);
        assertThat(f.h.count("lr_audit_log WHERE action = 'WITHDRAWAL_PAYEE_VIEW'")).isEqualTo(1);
        assertThat(f.h.jdbc.queryForObject("SELECT COUNT(*) FROM lr_withdrawal WHERE CAST(payee_cipher AS CHAR) LIKE '%13800001111%'", Integer.class))
                .isZero();
    }

    @Test
    void rejectReleasesInFlightAmount() {
        Withdrawals.View w = f.withdrawals.apply(A1.memberId(), 800, PAYEE);
        assertThat(f.withdrawals.summary(A1.memberId()).availableFen()).isZero();
        f.withdrawals.reject(w.id(), OP, "收款账号有误");
        assertThat(f.withdrawals.summary(A1.memberId()).availableFen()).isEqualTo(800);
        assertThat(f.withdrawals.mine(A1.memberId())).singleElement().satisfies(v -> {
            assertThat(v.status()).isEqualTo("REJECTED");
            assertThat(v.note()).isEqualTo("收款账号有误");
        });
        assertThatThrownBy(() -> f.withdrawals.markPaid(w.id(), OP, "x", null)).isInstanceOf(UserFacingException.class);
    }

    @Test
    void cannotPayBeforeApprovalWhenApprovalRequired() {
        Withdrawals.View w = f.withdrawals.apply(A1.memberId(), 100, PAYEE);
        assertThatThrownBy(() -> f.withdrawals.markPaid(w.id(), OP, "x", null)).isInstanceOf(UserFacingException.class).hasMessageContaining("待审核");
    }

    @Test
    void approvalSwitchOffGoesStraightToAwaitingPayout() {
        f.policies.save(new PolicyInput("GLOBAL_DEFAULT", "PAID_RATE", "GLOBAL", null, new BigDecimal("0.10"), new BigDecimal("0.8"),
                new BigDecimal("0.2"), false, 0, "DELIVERED", false, null), OP);
        Withdrawals.View w = f.withdrawals.apply(A1.memberId(), 100, PAYEE);
        assertThat(w.status()).isEqualTo("APPROVED");
        f.withdrawals.markPaid(w.id(), OP, "x", null);
        assertThat(f.ledger.balance("D1").withdrawnFen()).isEqualTo(100);
    }

    @Test
    void guards() {
        assertThatThrownBy(() -> f.withdrawals.apply("m-nobody", 100, PAYEE)).isInstanceOf(UserFacingException.class).hasMessageContaining("代理");
        assertThatThrownBy(() -> f.withdrawals.apply(A1.memberId(), 99, PAYEE)).isInstanceOf(UserFacingException.class).hasMessageContaining("最低");
        assertThatThrownBy(() -> f.withdrawals.apply(A1.memberId(), 100, new Payee("ALIPAY", "", "1"))).isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> f.withdrawals.apply(A1.memberId(), 100, new Payee("CASH", "张三", "1"))).isInstanceOf(UserFacingException.class);
        assertThat(f.h.count("lr_withdrawal")).isZero();
    }

    @Test
    void summaryForNonAgentSaysSo() {
        assertThat(f.withdrawals.summary("m-nobody").agent()).isFalse();
        Withdrawals.Summary s = f.withdrawals.summary(A1.memberId());
        assertThat(s.agent()).isTrue();
        assertThat(s.agentName()).isEqualTo("一级代理");
        assertThat(s.settleableFen()).isEqualTo(800);
        assertThat(s.minWithdrawFen()).isEqualTo(100);
        assertThat(s.approvalRequired()).isTrue();
        assertThat(f.ledger.entries("D1", 1, 20).records()).singleElement().satisfies(e -> {
            assertThat(e.typeText()).isEqualTo("佣金入账");
            assertThat(e.amountFen()).isEqualTo(800);
            assertThat(e.statusText()).isEqualTo("可提现");
        });
    }
}
