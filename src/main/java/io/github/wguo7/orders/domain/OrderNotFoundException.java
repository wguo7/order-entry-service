package io.github.wguo7.orders.domain;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(UUID id) {
        super("order " + id + " not found");
    }
}
