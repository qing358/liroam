package com.liroam.common.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StateMachineTest {

    enum Light { RED, GREEN, YELLOW, BROKEN }

    private final StateMachine<Light> machine = StateMachine.of(Light.class)
            .allow(Light.RED, Light.GREEN)
            .allow(Light.GREEN, Light.YELLOW)
            .allow(Light.YELLOW, Light.RED)
            .allowFromEach(java.util.EnumSet.of(Light.RED, Light.GREEN, Light.YELLOW), Light.BROKEN)
            .build();

    @Test
    void onlyDeclaredMovesAreAllowed() {
        assertThat(machine.canMove(Light.RED, Light.GREEN)).isTrue();
        assertThat(machine.canMove(Light.RED, Light.YELLOW)).isFalse();
        assertThat(machine.canMove(Light.RED, Light.RED)).isFalse();
        assertThat(machine.nextOf(Light.BROKEN)).isEmpty();
        assertThat(machine.isTerminal(Light.BROKEN)).isTrue();
    }

    @Test
    void illegalMoveNamesBothStates() {
        assertThatThrownBy(() -> machine.requireMove(Light.BROKEN, Light.RED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("BROKEN").hasMessageContaining("RED");
    }
}
