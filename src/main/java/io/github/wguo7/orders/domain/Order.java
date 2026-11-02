package io.github.wguo7.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An order and the rules for changing it. Every change returns a new Order with
 * version + 1; the store writes it only if the stored version is still the old one.
 */
public record Order(
        UUID id,
        String account,
        String symbol,
        Side side,
        OrderType type,
        long quantity,
        BigDecimal limitPrice,
        long filledQuantity,
        OrderStatus status,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public static Order create(UUID id, String account, String symbol, Side side, OrderType type,
                               long quantity, BigDecimal limitPrice, Instant now) {
        if (quantity <= 0) {
            throw new RuleViolationException("quantity must be positive");
        }
        if ((type == OrderType.LIMIT) != (limitPrice != null)) {
            throw new RuleViolationException("limitPrice is required for LIMIT orders and not allowed for MARKET orders");
        }
        if (limitPrice != null && limitPrice.signum() <= 0) {
            throw new RuleViolationException("limitPrice must be positive");
        }
        return new Order(id, account, symbol, side, type, quantity, limitPrice, 0, OrderStatus.NEW, 1, now, now);
    }

    public long remaining() {
        return quantity - filledQuantity;
    }

    /** Changes quantity, limit price or both. Quantity cannot drop to or below what has filled. */
    public Order amend(Long newQuantity, BigDecimal newLimitPrice, Instant now) {
        requireOpen("amend");
        if (newQuantity == null && newLimitPrice == null) {
            throw new RuleViolationException("amend needs quantity or limitPrice");
        }
        long q = newQuantity == null ? quantity : newQuantity;
        if (q <= filledQuantity) {
            throw new RuleViolationException(
                    "quantity " + q + " must exceed the filled quantity " + filledQuantity + "; cancel instead");
        }
        BigDecimal p = limitPrice;
        if (newLimitPrice != null) {
            if (type == OrderType.MARKET) {
                throw new RuleViolationException("a MARKET order has no limit price to amend");
            }
            if (newLimitPrice.signum() <= 0) {
                throw new RuleViolationException("limitPrice must be positive");
            }
            p = newLimitPrice;
        }
        boolean priceChanged = newLimitPrice != null && newLimitPrice.compareTo(limitPrice) != 0;
        if (q == quantity && !priceChanged) {
            throw new RuleViolationException("amend changes nothing");
        }
        return new Order(id, account, symbol, side, type, q, p, filledQuantity, status, version + 1, createdAt, now);
    }

    public Order cancel(Instant now) {
        requireOpen("cancel");
        return withStatus(OrderStatus.CANCELED, filledQuantity, now);
    }

    /** Applies an execution. A limit order fills only at its limit or better. */
    public Order fill(long qty, BigDecimal price, Instant now) {
        requireOpen("fill");
        if (qty <= 0) {
            throw new RuleViolationException("fill quantity must be positive");
        }
        if (qty > remaining()) {
            throw new RuleViolationException("fill of " + qty + " exceeds the remaining " + remaining());
        }
        if (price.signum() <= 0) {
            throw new RuleViolationException("fill price must be positive");
        }
        if (type == OrderType.LIMIT) {
            boolean worse = side == Side.BUY ? price.compareTo(limitPrice) > 0 : price.compareTo(limitPrice) < 0;
            if (worse) {
                throw new RuleViolationException("fill price " + price + " is worse than the limit " + limitPrice);
            }
        }
        long filled = filledQuantity + qty;
        return withStatus(filled == quantity ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED, filled, now);
    }

    private void requireOpen(String action) {
        if (!status.isOpen()) {
            throw new IllegalTransitionException("cannot " + action + " a " + status + " order");
        }
    }

    private Order withStatus(OrderStatus next, long filled, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new IllegalTransitionException("cannot move from " + status + " to " + next);
        }
        return new Order(id, account, symbol, side, type, quantity, limitPrice, filled, next, version + 1, createdAt, now);
    }
}
