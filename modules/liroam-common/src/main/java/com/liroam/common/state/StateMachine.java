package com.liroam.common.state;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 枚举状态的合法迁移表；持久化时配合 {@code UPDATE … WHERE status=? AND version=?} 使用。 */
public final class StateMachine<E extends Enum<E>> {

    private final Class<E> type;
    private final Map<E, Set<E>> next;

    private StateMachine(Class<E> type, Map<E, Set<E>> next) {
        this.type = type;
        this.next = next;
    }

    public static <E extends Enum<E>> Builder<E> of(Class<E> type) {
        return new Builder<>(type);
    }

    public boolean canMove(E from, E to) {
        return next.get(from).contains(to);
    }

    public void requireMove(E from, E to) {
        if (!canMove(from, to)) {
            throw new IllegalStateTransitionException(type.getSimpleName() + " 不允许 " + from + " → " + to);
        }
    }

    public Set<E> nextOf(E from) {
        return Collections.unmodifiableSet(next.get(from));
    }

    public boolean isTerminal(E state) {
        return next.get(state).isEmpty();
    }

    public static final class Builder<E extends Enum<E>> {
        private final Class<E> type;
        private final Map<E, Set<E>> next;

        private Builder(Class<E> type) {
            this.type = type;
            this.next = new EnumMap<>(type);
            for (E e : type.getEnumConstants()) {
                next.put(e, EnumSet.noneOf(type));
            }
        }

        @SafeVarargs
        public final Builder<E> allow(E from, E... to) {
            next.get(from).addAll(Set.of(to));
            return this;
        }

        public Builder<E> allowFromEach(Set<E> froms, E to) {
            froms.forEach(f -> next.get(f).add(to));
            return this;
        }

        public StateMachine<E> build() {
            return new StateMachine<>(type, next);
        }
    }
}
