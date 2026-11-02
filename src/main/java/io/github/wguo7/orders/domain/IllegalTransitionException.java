package io.github.wguo7.orders.domain;

/** The order's current status does not allow the requested action (HTTP 409). */
public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(String message) {
        super(message);
    }
}
