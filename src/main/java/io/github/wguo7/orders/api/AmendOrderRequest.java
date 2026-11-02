package io.github.wguo7.orders.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

public record AmendOrderRequest(
        @Positive @Max(1_000_000_000L) Long quantity,
        @Positive @Digits(integer = 14, fraction = 4) BigDecimal limitPrice) {}
