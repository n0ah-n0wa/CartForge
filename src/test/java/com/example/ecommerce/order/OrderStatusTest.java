package com.example.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({
        "PENDING, CONFIRMED",
        "PENDING, CANCELLED",
        "CONFIRMED, PROCESSING",
        "CONFIRMED, CANCELLED",
        "PROCESSING, SHIPPED",
        "SHIPPED, DELIVERED"
    })
    void allowsTheSpecifiedTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "DELIVERED, PENDING",
        "DELIVERED, CANCELLED",
        "CANCELLED, CONFIRMED",
        "SHIPPED, PROCESSING",
        "PENDING, SHIPPED",
        "PENDING, DELIVERED",
        "PROCESSING, CANCELLED",
        "CONFIRMED, PENDING"
    })
    void rejectsTransitionsOutsideTheLifecycle(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void treatsDeliveredAndCancelledAsTerminal() {
        assertThat(OrderStatus.DELIVERED.isTerminal()).isTrue();
        assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(OrderStatus.PENDING.isTerminal()).isFalse();
    }

    @Test
    void allowsCancellationOnlyBeforeProcessing() {
        assertThat(OrderStatus.PENDING.isCancellable()).isTrue();
        assertThat(OrderStatus.CONFIRMED.isCancellable()).isTrue();
        assertThat(OrderStatus.PROCESSING.isCancellable()).isFalse();
        assertThat(OrderStatus.SHIPPED.isCancellable()).isFalse();
        assertThat(OrderStatus.DELIVERED.isCancellable()).isFalse();
    }

    @Test
    void neverTransitionsToANullTarget() {
        assertThat(OrderStatus.PENDING.canTransitionTo(null)).isFalse();
    }
}
