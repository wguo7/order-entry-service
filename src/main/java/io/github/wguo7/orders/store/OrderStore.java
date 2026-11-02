package io.github.wguo7.orders.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.wguo7.orders.domain.Order;
import io.github.wguo7.orders.domain.OrderStatus;
import io.github.wguo7.orders.domain.OrderType;
import io.github.wguo7.orders.domain.Side;

/** SQL for orders, their audit events and idempotency keys. Callers own the transaction. */
@Repository
public class OrderStore {

    public record Event(int seq, String type, Map<String, Object> detail, Instant at) {}

    public record StoredKey(String requestHash, UUID orderId) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OrderStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Order o) {
        jdbc.sql("""
                INSERT INTO orders (id, account, symbol, side, type, quantity, limit_price, filled_quantity,
                                    status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(o.id(), o.account(), o.symbol(), o.side().name(), o.type().name(), o.quantity(),
                        o.limitPrice(), o.filledQuantity(), o.status().name(), o.version(),
                        Timestamp.from(o.createdAt()), Timestamp.from(o.updatedAt()))
                .update();
    }

    /** Writes a changed order. Returns false if another writer changed it first. */
    public boolean update(Order o, int expectedVersion) {
        return jdbc.sql("""
                UPDATE orders SET quantity = ?, limit_price = ?, filled_quantity = ?, status = ?, version = ?,
                                  updated_at = ?
                WHERE id = ? AND version = ?""")
                .params(o.quantity(), o.limitPrice(), o.filledQuantity(), o.status().name(), o.version(),
                        Timestamp.from(o.updatedAt()), o.id(), expectedVersion)
                .update() == 1;
    }

    public Optional<Order> find(UUID id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = ?").param(id).query(OrderStore::order).optional();
    }

    /** Reads the order and holds its row lock until the transaction ends. */
    public Optional<Order> lock(UUID id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = ? FOR UPDATE").param(id).query(OrderStore::order).optional();
    }

    public List<Order> list(String account, OrderStatus status, int limit) {
        return jdbc.sql("""
                SELECT * FROM orders WHERE account = ? AND (CAST(? AS TEXT) IS NULL OR status = ?)
                ORDER BY created_at DESC, id LIMIT ?""")
                .params(account, status == null ? null : status.name(), status == null ? null : status.name(), limit)
                .query(OrderStore::order).list();
    }

    /** The event's seq is the order version it produced, so the primary key rejects a lost update. */
    public void appendEvent(UUID orderId, int seq, String type, Map<String, Object> detail, Instant at) {
        jdbc.sql("INSERT INTO order_events (order_id, seq, type, detail, at) VALUES (?, ?, ?, CAST(? AS JSONB), ?)")
                .params(orderId, seq, type, toJson(detail), Timestamp.from(at))
                .update();
    }

    @SuppressWarnings("unchecked")
    public List<Event> events(UUID orderId) {
        return jdbc.sql("SELECT seq, type, detail::text AS detail, at FROM order_events WHERE order_id = ? ORDER BY seq")
                .param(orderId)
                .query((rs, n) -> new Event(rs.getInt("seq"), rs.getString("type"),
                        fromJson(rs.getString("detail")), rs.getTimestamp("at").toInstant()))
                .list();
    }

    /**
     * Records the key for a new order. Returns false if the key already exists. A concurrent
     * insert of the same key waits on the primary key until the first transaction ends, so two
     * requests with one key cannot both succeed.
     */
    public boolean claimKey(String account, String key, String requestHash, UUID orderId, Instant at) {
        return jdbc.sql("""
                INSERT INTO idempotency_keys (account, key, request_hash, order_id, created_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""")
                .params(account, key, requestHash, orderId, Timestamp.from(at))
                .update() == 1;
    }

    public Optional<StoredKey> findKey(String account, String key) {
        return jdbc.sql("SELECT request_hash, order_id FROM idempotency_keys WHERE account = ? AND key = ?")
                .params(account, key)
                .query((rs, n) -> new StoredKey(rs.getString("request_hash"), rs.getObject("order_id", UUID.class)))
                .optional();
    }

    private static Order order(ResultSet rs, int n) throws SQLException {
        return new Order(
                rs.getObject("id", UUID.class),
                rs.getString("account"),
                rs.getString("symbol"),
                Side.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("type")),
                rs.getLong("quantity"),
                rs.getBigDecimal("limit_price"),
                rs.getLong("filled_quantity"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String toJson(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String s) {
        try {
            return json.readValue(s, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
