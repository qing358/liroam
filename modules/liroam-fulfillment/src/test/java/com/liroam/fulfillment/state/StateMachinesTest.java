package com.liroam.fulfillment.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.liroam.common.state.IllegalStateTransitionException;
import com.liroam.common.state.StateMachine;

/**
 * CT-FUL-05：每个状态机的全部 from×to 组合与 domain-design 原表逐格比对，非法迁移全部拒绝。
 * 期望表按文档逐行抄写，不从实现推导。
 */
class StateMachinesTest {

    /** 每行 "FROM: TO TO …"；没有出现的状态即终态。 */
    private static Map<String, Set<String>> table(String... rows) {
        Map<String, Set<String>> m = new HashMap<>();
        for (String row : rows) {
            String[] parts = row.split(":", -1);
            m.put(parts[0].trim(), Arrays.stream(parts[1].trim().split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.toSet()));
        }
        return m;
    }

    private static <E extends Enum<E>> void assertExactly(Class<E> type, StateMachine<E> machine, Map<String, Set<String>> expected) {
        int checked = 0;
        for (E from : type.getEnumConstants()) {
            Set<String> allowed = expected.getOrDefault(from.name(), Set.of());
            for (E to : type.getEnumConstants()) {
                boolean ok = allowed.contains(to.name());
                assertThat(machine.canMove(from, to)).as("%s %s → %s", type.getSimpleName(), from, to).isEqualTo(ok);
                if (!ok) {
                    assertThatThrownBy(() -> machine.requireMove(from, to)).isInstanceOf(IllegalStateTransitionException.class);
                }
                checked++;
            }
        }
        assertThat(checked).isEqualTo(type.getEnumConstants().length * type.getEnumConstants().length);
    }

    @Test
    void cardAsset_matchesSection7_3() {
        assertExactly(CardAssetStatus.class, CardAssetStatus.MACHINE, table(
                "CREATED: ALLOCATED CANCELLED EXCEPTION",
                "ALLOCATED: PROVISIONED CANCELLED EXCEPTION",
                "PROVISIONED: DELIVERED CANCELLED TERMINATED EXCEPTION",
                "DELIVERED: IN_USE SUSPENDED TERMINATED EXPIRED",
                "IN_USE: SUSPENDED EXPIRED TERMINATED",
                "SUSPENDED: IN_USE DELIVERED TERMINATED",
                "EXPIRED: IN_USE TERMINATED",
                "EXCEPTION: CREATED ALLOCATED PROVISIONED CANCELLED TERMINATED"));
        assertThat(CardAssetStatus.MACHINE.isTerminal(CardAssetStatus.CANCELLED)).isTrue();
        assertThat(CardAssetStatus.MACHINE.isTerminal(CardAssetStatus.TERMINATED)).isTrue();
    }

    @Test
    void cardAsset_exceptionReturnsOnlyToWhereItCameFrom() {
        assertThat(CardAssetStatus.canLeaveException(CardAssetStatus.ALLOCATED, CardAssetStatus.ALLOCATED)).isTrue();
        assertThat(CardAssetStatus.canLeaveException(CardAssetStatus.ALLOCATED, CardAssetStatus.PROVISIONED)).isFalse();
        assertThat(CardAssetStatus.canLeaveException(CardAssetStatus.ALLOCATED, CardAssetStatus.CREATED)).isFalse();
        assertThat(CardAssetStatus.canLeaveException(CardAssetStatus.PROVISIONED, CardAssetStatus.CANCELLED)).isTrue();
        assertThat(CardAssetStatus.canLeaveException(CardAssetStatus.CREATED, CardAssetStatus.TERMINATED)).isTrue();
    }

    @Test
    void cardPlan_matchesSection7_4() {
        assertExactly(CardPlanStatus.class, CardPlanStatus.MACHINE, table(
                "PENDING: PROVISIONING CANCELLED FAILED",
                "PROVISIONING: READY CANCELLED FAILED",
                "READY: IN_USE",
                "IN_USE: DEPLETED EXPIRED"));
    }

    @Test
    void supplierCommand_matchesSection6_3() {
        assertExactly(CommandStatus.class, CommandStatus.MACHINE, table(
                "PENDING: RUNNING CANCELLED",
                "RUNNING: SUCCEEDED PENDING MANUAL LOOKUP_PENDING CANCELLED",
                "LOOKUP_PENDING: SUCCEEDED PENDING MANUAL CANCELLED",
                "MANUAL: PENDING SUCCEEDED CANCELLED"));
    }

    @Test
    void fulfillmentLine_matchesSection7_2() {
        assertExactly(FulfillmentLineStatus.class, FulfillmentLineStatus.MACHINE, table(
                "RECEIVED: EXPANDED CANCELLED",
                "EXPANDED: IN_PROGRESS PARTIAL CANCELLED PARTIAL_REFUNDED",
                "IN_PROGRESS: COMPLETED PARTIAL CANCELLED PARTIAL_REFUNDED",
                "COMPLETED: CANCELLED PARTIAL_REFUNDED",
                "PARTIAL: CANCELLED PARTIAL_REFUNDED"));
    }
}
