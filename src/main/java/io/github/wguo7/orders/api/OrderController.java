package io.github.wguo7.orders.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ProblemDetail;

import io.github.wguo7.orders.OrderService;
import io.github.wguo7.orders.domain.OrderStatus;
import io.github.wguo7.orders.store.OrderStore;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    /** 201 for a new order; 200 with Idempotent-Replayed: true when the key already created one. */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                @Valid @RequestBody CreateOrderRequest body) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw badRequest("Idempotency-Key header must be 1 to 128 characters");
        }
        OrderService.Created c = orders.create(body.toNewOrder(), key);
        OrderResponse r = OrderResponse.of(c.order());
        if (c.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(r);
        }
        return ResponseEntity.created(URI.create("/orders/" + r.id())).body(r);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return OrderResponse.of(orders.get(id));
    }

    @GetMapping
    public List<OrderResponse> list(@RequestParam String account, @RequestParam(required = false) OrderStatus status,
                                    @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 500) {
            throw badRequest("limit must be between 1 and 500");
        }
        return orders.list(account, status, limit).stream().map(OrderResponse::of).toList();
    }

    @PatchMapping("/{id}")
    public OrderResponse amend(@PathVariable UUID id, @Valid @RequestBody AmendOrderRequest body) {
        return OrderResponse.of(orders.amend(id, body.quantity(), body.limitPrice()));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@PathVariable UUID id) {
        return OrderResponse.of(orders.cancel(id));
    }

    @PostMapping("/{id}/fills")
    public OrderResponse fill(@PathVariable UUID id, @Valid @RequestBody FillRequest body) {
        return OrderResponse.of(orders.fill(id, body.quantity(), body.price()));
    }

    @GetMapping("/{id}/events")
    public List<OrderStore.Event> events(@PathVariable UUID id) {
        return orders.events(id);
    }

    private static ErrorResponseException badRequest(String detail) {
        return new ErrorResponseException(HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
