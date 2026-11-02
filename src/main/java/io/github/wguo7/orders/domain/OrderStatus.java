package io.github.wguo7.orders.domain;

import java.util.EnumSet;
import java.util.Set;

/** Order lifecycle. FILLED and CANCELED are terminal. */
public enum OrderStatus {
    NEW, PARTIALLY_FILLED, FILLED, CANCELED;

    public boolean isOpen() {
        return this == NEW || this == PARTIALLY_FILLED;
    }

    public Set<OrderStatus> next() {
        return switch (this) {
            case NEW, PARTIALLY_FILLED -> EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELED);
            case FILLED, CANCELED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canMoveTo(OrderStatus target) {
        return next().contains(target);
    }
}
