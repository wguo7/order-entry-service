package io.github.wguo7.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.wguo7.orders.domain.IllegalTransitionException;
import io.github.wguo7.orders.domain.Order;
import io.github.wguo7.orders.domain.OrderStatus;
import io.github.wguo7.orders.domain.OrderType;
import io.github.wguo7.orders.domain.RuleViolationException;
import io.github.wguo7.orders.domain.Side;

class OrderTest {

    static final Instant T0 = Instant.parse("2026-11-02T14:30:00Z");
    static final Instant T1 = T0.plusSeconds(1);

    static Order limit(Side side, long qty, String price) {
        return Order.create(UUID.randomUUID(), "acct", "AAPL", side, OrderType.LIMIT, qty, new BigDecimal(price), T0);
    }

    static Order market(long qty) {
        return Order.create(UUID.randomUUID(), "acct", "AAPL", Side.BUY, OrderType.MARKET, qty, null, T0);
    }

    @Test
    void newOrderStartsAtVersionOne() {
        Order o = limit(Side.BUY, 100, "10.00");
        assertThat(o.status()).isEqualTo(OrderStatus.NEW);
        assertThat(o.version()).isEqualTo(1);
        assertThat(o.remaining()).isEqualTo(100);
    }

    @Test
    void createRejectsPriceTypeMismatch() {
        assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "a", "X", Side.BUY, OrderType.LIMIT, 1, null, T0))
                .isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "a", "X", Side.BUY, OrderType.MARKET, 1,
                BigDecimal.ONE, T0)).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> limit(Side.BUY, 0, "1")).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> limit(Side.BUY, 1, "0")).isInstanceOf(RuleViolationException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "NEW, PARTIALLY_FILLED, true", "NEW, FILLED, true", "NEW, CANCELED, true", "NEW, NEW, false",
            "PARTIALLY_FILLED, PARTIALLY_FILLED, true", "PARTIALLY_FILLED, FILLED, true",
            "PARTIALLY_FILLED, CANCELED, true", "PARTIALLY_FILLED, NEW, false",
            "FILLED, CANCELED, false", "FILLED, PARTIALLY_FILLED, false",
            "CANCELED, FILLED, false", "CANCELED, NEW, false"})
    void transitions(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"FILLED", "CANCELED"})
    void terminalStatesAllowNothing(OrderStatus s) {
        assertThat(s.isOpen()).isFalse();
        assertThat(s.next()).isEmpty();
    }

    @Test
    void partialThenFullFill() {
        Order o = limit(Side.BUY, 100, "10.00").fill(40, new BigDecimal("9.99"), T1);
        assertThat(o.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(o.filledQuantity()).isEqualTo(40);
        assertThat(o.version()).isEqualTo(2);
        o = o.fill(60, new BigDecimal("10.00"), T1);
        assertThat(o.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(o.remaining()).isZero();
        Order filled = o;
        assertThatThrownBy(() -> filled.fill(1, BigDecimal.TEN, T1)).isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> filled.cancel(T1)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void fillCannotExceedRemaining() {
        Order o = limit(Side.BUY, 100, "10").fill(90, BigDecimal.TEN, T1);
        assertThatThrownBy(() -> o.fill(11, BigDecimal.TEN, T1))
                .isInstanceOf(RuleViolationException.class).hasMessageContaining("remaining 10");
    }

    @ParameterizedTest
    @CsvSource({"BUY, 10.01, false", "BUY, 10.00, true", "BUY, 9.50, true",
                "SELL, 9.99, false", "SELL, 10.00, true", "SELL, 10.50, true"})
    void limitOrderFillsOnlyAtLimitOrBetter(Side side, String price, boolean ok) {
        Order o = limit(side, 10, "10.00");
        if (ok) {
            assertThat(o.fill(10, new BigDecimal(price), T1).status()).isEqualTo(OrderStatus.FILLED);
        } else {
            assertThatThrownBy(() -> o.fill(10, new BigDecimal(price), T1)).isInstanceOf(RuleViolationException.class);
        }
    }

    @Test
    void marketOrderFillsAtAnyPositivePrice() {
        assertThat(market(5).fill(5, new BigDecimal("123.45"), T1).status()).isEqualTo(OrderStatus.FILLED);
        assertThatThrownBy(() -> market(5).fill(5, BigDecimal.ZERO, T1)).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void amendQuantityAndPrice() {
        Order o = limit(Side.BUY, 100, "10.00").amend(150L, new BigDecimal("10.25"), T1);
        assertThat(o.quantity()).isEqualTo(150);
        assertThat(o.limitPrice()).isEqualByComparingTo("10.25");
        assertThat(o.version()).isEqualTo(2);
        assertThat(o.updatedAt()).isEqualTo(T1);
        assertThat(o.createdAt()).isEqualTo(T0);
    }

    @Test
    void amendRules() {
        Order part = limit(Side.BUY, 100, "10.00").fill(60, BigDecimal.TEN, T1);
        assertThatThrownBy(() -> part.amend(60L, null, T1)).hasMessageContaining("cancel instead");
        assertThat(part.amend(61L, null, T1).remaining()).isEqualTo(1);
        assertThatThrownBy(() -> part.amend(null, null, T1)).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> part.amend(100L, new BigDecimal("10.0000"), T1)).hasMessageContaining("changes nothing");
        assertThatThrownBy(() -> market(5).amend(null, BigDecimal.ONE, T1)).hasMessageContaining("MARKET");
        assertThatThrownBy(() -> part.amend(null, new BigDecimal("-1"), T1)).isInstanceOf(RuleViolationException.class);
        Order canceled = part.cancel(T1);
        assertThatThrownBy(() -> canceled.amend(200L, null, T1)).isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void cancelKeepsFilledQuantity() {
        Order o = limit(Side.SELL, 100, "10").fill(30, BigDecimal.TEN, T1).cancel(T1);
        assertThat(o.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(o.filledQuantity()).isEqualTo(30);
        assertThat(o.version()).isEqualTo(3);
    }

    @Test
    void requestHashIgnoresPriceScaleButNotFields() {
        var a = new OrderService.NewOrder("a", "AAPL", Side.BUY, OrderType.LIMIT, 10, new BigDecimal("10.5"));
        var b = new OrderService.NewOrder("a", "AAPL", Side.BUY, OrderType.LIMIT, 10, new BigDecimal("10.5000"));
        var c = new OrderService.NewOrder("a", "AAPL", Side.BUY, OrderType.LIMIT, 11, new BigDecimal("10.5"));
        assertThat(OrderService.hash(a)).isEqualTo(OrderService.hash(b)).isNotEqualTo(OrderService.hash(c));
    }
}
