package com.liroam.common.state;

public class IllegalStateTransitionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public IllegalStateTransitionException(String message) {
        super(message);
    }
}
