package io.github.wguo7.orders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.wguo7.orders.domain.IllegalTransitionException;
import io.github.wguo7.orders.domain.Order;
import io.github.wguo7.orders.domain.OrderNotFoundException;
import io.github.wguo7.orders.domain.OrderStatus;
import io.github.wguo7.orders.domain.OrderType;
import io.github.wguo7.orders.domain.Side;
import io.github.wguo7.orders.store.OrderStore;

@Service
public class OrderService {

    public record NewOrder(String account, String symbol, Side side, OrderType type, long quantity,
                           BigDecimal limitPrice) {}

    /** replayed is true when the idempotency key had already created this order. */
    public record Created(Order order, boolean replayed) {}

    public static class KeyReusedException extends RuntimeException {
        public KeyReusedException() {
            super("Idempotency-Key was already used with a different request");
        }
    }

    private static class KeyTaken extends RuntimeException {
        KeyTaken() {
            super(null, null, false, false);
        }
    }

    private final OrderStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderService(OrderStore store, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Creates the order, its CREATED event and the key row in one transaction. If the key is
     * already taken, that transaction rolls back and the order the key created is returned.
     */
    public Created create(NewOrder req, String idempotencyKey) {
        String hash = hash(req);
        try {
            Order created = tx.execute(s -> {
                Instant now = clock.instant();
                Order o = Order.create(UUID.randomUUID(), req.account(), req.symbol(), req.side(), req.type(),
                        req.quantity(), req.limitPrice(), now);
                store.insert(o);
                if (!store.claimKey(req.account(), idempotencyKey, hash, o.id(), now)) {
                    throw new KeyTaken();
                }
                store.appendEvent(o.id(), o.version(), "CREATED", snapshot(o), now);
                return o;
            });
            return new Created(created, false);
        } catch (KeyTaken e) {
            OrderStore.StoredKey k = store.findKey(req.account(), idempotencyKey).orElseThrow();
            if (!k.requestHash().equals(hash)) {
                throw new KeyReusedException();
            }
            return new Created(get(k.orderId()), true);
        }
    }

    public Order get(UUID id) {
        return store.find(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> list(String account, OrderStatus status, int limit) {
        return store.list(account, status, limit);
    }

    public List<OrderStore.Event> events(UUID id) {
        get(id);
        return store.events(id);
    }

    public Order amend(UUID id, Long quantity, BigDecimal limitPrice) {
        return change(id, o -> o.amend(quantity, limitPrice, clock.instant()), (before, after) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("quantity", Map.of("from", before.quantity(), "to", after.quantity()));
            if (after.limitPrice() != null) {
                d.put("limitPrice", Map.of("from", before.limitPrice(), "to", after.limitPrice()));
            }
            return d;
        }, "AMENDED");
    }

    public Order cancel(UUID id) {
        return change(id, o -> o.cancel(clock.instant()),
                (before, after) -> Map.of("canceledQuantity", after.remaining()), "CANCELED");
    }

    public Order fill(UUID id, long quantity, BigDecimal price) {
        return change(id, o -> o.fill(quantity, price, clock.instant()),
                (before, after) -> Map.of("quantity", quantity, "price", price, "filledQuantity", after.filledQuantity()),
                null);
    }

    private interface Detail {
        Map<String, Object> of(Order before, Order after);
    }

    /** Locks the row, applies the change, writes the order and one event. eventType null means the new status. */
    private Order change(UUID id, UnaryOperator<Order> f, Detail detail, String eventType) {
        return tx.execute(s -> {
            Order before = store.lock(id).orElseThrow(() -> new OrderNotFoundException(id));
            Order after = f.apply(before);
            if (!store.update(after, before.version())) {
                throw new IllegalTransitionException("order " + id + " changed concurrently");
            }
            store.appendEvent(id, after.version(), eventType != null ? eventType : after.status().name(),
                    detail.of(before, after), after.updatedAt());
            return after;
        });
    }

    private static Map<String, Object> snapshot(Order o) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("symbol", o.symbol());
        d.put("side", o.side());
        d.put("type", o.type());
        d.put("quantity", o.quantity());
        if (o.limitPrice() != null) {
            d.put("limitPrice", o.limitPrice());
        }
        return d;
    }

    /** Hash of the fields that define the order; prices compare by value, so 10.5 and 10.50 match. */
    static String hash(NewOrder r) {
        String price = r.limitPrice() == null ? "" : r.limitPrice().stripTrailingZeros().toPlainString();
        String canonical = String.join("|", r.account(), r.symbol(), String.valueOf(r.side()),
                String.valueOf(r.type()), Long.toString(r.quantity()), price);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
