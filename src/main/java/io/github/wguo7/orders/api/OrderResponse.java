package io.github.wguo7.orders.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.github.wguo7.orders.domain.Order;
import io.github.wguo7.orders.domain.OrderStatus;
import io.github.wguo7.orders.domain.OrderType;
import io.github.wguo7.orders.domain.Side;

public record OrderResponse(UUID id, String account, String symbol, Side side, OrderType type, long quantity,
                            BigDecimal limitPrice, long filledQuantity, long remainingQuantity, OrderStatus status,
                            int version, Instant createdAt, Instant updatedAt) {

    static OrderResponse of(Order o) {
        return new OrderResponse(o.id(), o.account(), o.symbol(), o.side(), o.type(), o.quantity(), o.limitPrice(),
                o.filledQuantity(), o.remaining(), o.status(), o.version(), o.createdAt(), o.updatedAt());
    }
}
