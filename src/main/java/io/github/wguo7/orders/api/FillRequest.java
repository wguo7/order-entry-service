package io.github.wguo7.orders.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** An execution report from the venue, applied to the order. */
public record FillRequest(
        @NotNull @Positive Long quantity,
        @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal price) {}
