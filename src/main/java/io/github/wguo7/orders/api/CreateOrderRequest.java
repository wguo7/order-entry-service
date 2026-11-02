package io.github.wguo7.orders.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import io.github.wguo7.orders.OrderService;
import io.github.wguo7.orders.domain.OrderType;
import io.github.wguo7.orders.domain.Side;

public record CreateOrderRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String account,
        @NotNull @Pattern(regexp = "[A-Z][A-Z0-9.]{0,9}") String symbol,
        @NotNull Side side,
        @NotNull OrderType type,
        @NotNull @Positive @Max(1_000_000_000L) Long quantity,
        @Positive @Digits(integer = 14, fraction = 4) BigDecimal limitPrice) {

    @AssertTrue(message = "limitPrice is required for LIMIT orders and not allowed for MARKET orders")
    public boolean isPriceMatchesType() {
        return type == null || (type == OrderType.LIMIT) == (limitPrice != null);
    }

    OrderService.NewOrder toNewOrder() {
        return new OrderService.NewOrder(account, symbol, side, type, quantity, limitPrice);
    }
}
