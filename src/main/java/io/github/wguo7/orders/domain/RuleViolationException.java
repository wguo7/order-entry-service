package io.github.wguo7.orders.domain;

/** The request is well formed but breaks a business rule for this order (HTTP 422). */
public class RuleViolationException extends RuntimeException {
    public RuleViolationException(String message) {
        super(message);
    }
}
