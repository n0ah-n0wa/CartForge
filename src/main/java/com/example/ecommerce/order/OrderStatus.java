package com.example.ecommerce.order;

import java.util.Set;

/**
 * Order lifecycle states and the transitions the specification allows.
 * {@code DELIVERED} and {@code CANCELLED} are terminal.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case PENDING -> Set.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> Set.of(PROCESSING, CANCELLED);
            case PROCESSING -> Set.of(SHIPPED);
            case SHIPPED -> Set.of(DELIVERED);
            case DELIVERED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return target != null && allowedTransitions().contains(target);
    }

    /**
     * Cancellation is only offered while the order has not shipped.
     */
    public boolean isCancellable() {
        return canTransitionTo(CANCELLED);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
